package org.wonagohdss.dashboard;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class DashboardRepository {
    private final DatabaseConfig config;
    private final String openhds;
    private final String odk;

    public DashboardRepository(DatabaseConfig config) {
        this.config = config;
        this.openhds = quoteSchema(config.getOpenhdsSchema());
        this.odk = quoteSchema(config.getOdkSchema());
    }

    public List<Map<String, Object>> getRounds() throws SQLException {
        List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        Map<String, Object> baseline = new LinkedHashMap<String, Object>();
        baseline.put("number", Integer.valueOf(0));
        baseline.put("label", "Baseline (Round 0)");
        baseline.put("startDate", null);
        baseline.put("endDate", null);
        rounds.add(baseline);

        Connection connection = config.openConnection();
        try {
            String sql = "SELECT roundNumber, startDate, endDate FROM " + openhds
                    + ".`round` WHERE roundNumber IS NOT NULL ORDER BY roundNumber";
            Statement statement = connection.createStatement();
            try {
                ResultSet result = statement.executeQuery(sql);
                try {
                    while (result.next()) {
                        int number = result.getInt("roundNumber");
                        if (number == 0) {
                            continue;
                        }
                        Map<String, Object> round = new LinkedHashMap<String, Object>();
                        round.put("number", Integer.valueOf(number));
                        round.put("label", "Round " + number);
                        round.put("startDate", result.getString("startDate"));
                        round.put("endDate", result.getString("endDate"));
                        rounds.add(round);
                    }
                } finally {
                    result.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
        return rounds;
    }

    public Map<String, Object> getDashboard(int roundNumber) throws SQLException {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        Connection connection = config.openConnection();
        try {
            response.put("round", getRoundInfo(connection, roundNumber));
        } finally {
            connection.close();
        }

        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            Future<Map<String, Object>> baseline = submitSection(executor, roundNumber,
                    new SectionLoader() {
                        public Map<String, Object> load(Connection sectionConnection, int round)
                                throws SQLException {
                            return getBaseline(sectionConnection);
                        }
                    });
            Future<Map<String, Object>> progress = submitSection(executor, roundNumber,
                    new SectionLoader() {
                        public Map<String, Object> load(Connection sectionConnection, int round)
                                throws SQLException {
                            return getProgress(sectionConnection, round);
                        }
                    });
            Future<Map<String, Object>> newBaseline = submitSection(executor, roundNumber,
                    new SectionLoader() {
                        public Map<String, Object> load(Connection sectionConnection, int round)
                                throws SQLException {
                            return getNewBaseline(sectionConnection, round);
                        }
                    });
            Future<Map<String, Object>> vitalEvents = submitSection(executor, roundNumber,
                    new SectionLoader() {
                        public Map<String, Object> load(Connection sectionConnection, int round)
                                throws SQLException {
                            Map<String, Object> section = new LinkedHashMap<String, Object>();
                            section.put("items", getVitalEvents(sectionConnection, round));
                            return section;
                        }
                    });
            Future<Map<String, Object>> eventUpdates = submitSection(executor, roundNumber,
                    new SectionLoader() {
                        public Map<String, Object> load(Connection sectionConnection, int round)
                                throws SQLException {
                            Map<String, Object> section = new LinkedHashMap<String, Object>();
                            section.put("items", getEventUpdates(sectionConnection, round));
                            return section;
                        }
                    });
            response.put("baseline", waitForSection(baseline, "baseline summary"));
            response.put("progress", waitForSection(progress, "round progress"));
            response.put("newBaseline", waitForSection(newBaseline, "new baseline records"));
            response.put("vitalEvents", waitForSection(vitalEvents, "vital events").get("items"));
            response.put("eventUpdates", waitForSection(eventUpdates, "event updates").get("items"));
        } finally {
            executor.shutdownNow();
        }

        response.put("generatedAt", Long.valueOf(System.currentTimeMillis()));
        return response;
    }

    private Future<Map<String, Object>> submitSection(ExecutorService executor, final int roundNumber,
                                                      final SectionLoader loader) {
        return executor.submit(new java.util.concurrent.Callable<Map<String, Object>>() {
            public Map<String, Object> call() throws SQLException {
                Connection connection = config.openConnection();
                try {
                    return loader.load(connection, roundNumber);
                } finally {
                    connection.close();
                }
            }
        });
    }

    private Map<String, Object> waitForSection(Future<Map<String, Object>> section, String name)
            throws SQLException {
        try {
            return section.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while loading " + name + ".", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException) {
                throw (SQLException) cause;
            }
            throw new SQLException("Could not load " + name + ".", cause);
        }
    }

    private interface SectionLoader {
        Map<String, Object> load(Connection connection, int roundNumber) throws SQLException;
    }

    public List<Map<String, Object>> getNotVisited(int roundNumber) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (roundNumber == 0) {
            return rows;
        }
        String sql = "SELECT h.houseid AS houseId, COALESCE(h.kebele, 'Unassigned') AS kebele, "
                + "COALESCE(h.cluster, 'Unassigned') AS cluster "
                + "FROM " + baselineHouseholdsSql() + " h "
                + "LEFT JOIN (SELECT DISTINCT OPENHDS_LOCATION_ID AS houseId FROM " + odk
                + ".`visit_registration_core` WHERE OPENHDS_ROUND_NUMBER = ? "
                + "AND OPENHDS_LOCATION_ID IS NOT NULL) v ON v.houseId = h.houseid "
                + "WHERE v.houseId IS NULL ORDER BY h.kebele, h.cluster, h.houseid";
        Connection connection = config.openConnection();
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                statement.setInt(1, roundNumber);
                ResultSet result = statement.executeQuery();
                try {
                    while (result.next()) {
                        Map<String, Object> row = new LinkedHashMap<String, Object>();
                        row.put("houseId", result.getString("houseId"));
                        row.put("kebele", result.getString("kebele"));
                        row.put("cluster", result.getString("cluster"));
                        rows.add(row);
                    }
                } finally {
                    result.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
        return rows;
    }

    public List<Map<String, Object>> getLocations(int roundNumber) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        String visitJoin = roundNumber == 0
                ? "LEFT JOIN (SELECT DISTINCT OPENHDS_LOCATION_ID AS houseId FROM " + odk
                    + ".`visit_registration_core` WHERE 1 = 0) v ON v.houseId = h.houseid "
                : "LEFT JOIN (SELECT DISTINCT OPENHDS_LOCATION_ID AS houseId FROM " + odk
                    + ".`visit_registration_core` WHERE OPENHDS_ROUND_NUMBER = ? "
                    + "AND OPENHDS_LOCATION_ID IS NOT NULL) v ON v.houseId = h.houseid ";
        String sql = "SELECT h.houseid AS houseId, COALESCE(h.kebele, 'Unassigned') AS kebele, "
                + "COALESCE(h.cluster, 'Unassigned') AS cluster, h.latitude, h.longitude, "
                + "CASE WHEN v.houseId IS NULL THEN 0 ELSE 1 END AS visited "
                + "FROM " + baselineHouseholdsSql() + " h " + visitJoin
                + "ORDER BY h.kebele, h.cluster, h.houseid";
        Connection connection = config.openConnection();
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                if (roundNumber != 0) {
                    statement.setInt(1, roundNumber);
                }
                ResultSet result = statement.executeQuery();
                try {
                    while (result.next()) {
                        Map<String, Object> row = new LinkedHashMap<String, Object>();
                        row.put("houseId", result.getString("houseId"));
                        row.put("kebele", result.getString("kebele"));
                        row.put("cluster", result.getString("cluster"));
                        row.put("latitude", parseNumber(result.getString("latitude")));
                        row.put("longitude", parseNumber(result.getString("longitude")));
                        row.put("visited", Boolean.valueOf(result.getInt("visited") == 1));
                        rows.add(row);
                    }
                } finally {
                    result.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
        return rows;
    }

    public List<Map<String, Object>> getExportRows(int roundNumber, String dimension) throws SQLException {
        Map<String, Object> dashboard = getDashboard(roundNumber);
        Map<String, Object> progress = asMap(dashboard.get("progress"));
        Object data = "worker".equals(dimension) ? progress.get("byWorker") : progress.get("byCluster");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data;
        return rows;
    }

    private Map<String, Object> getBaseline(Connection connection) throws SQLException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("households", Long.valueOf(scalar(connection,
                "SELECT COUNT(DISTINCT houseid) FROM " + openhds + ".`allpopkebele2` WHERE houseid IS NOT NULL")));
        data.put("population", Long.valueOf(scalar(connection,
                "SELECT COUNT(DISTINCT uuid) FROM " + openhds + ".`allpopkebele` WHERE uuid IS NOT NULL")));
        data.put("socialGroups", Long.valueOf(scalar(connection,
                "SELECT COUNT(*) FROM " + openhds + ".`socialgroup` WHERE deleted = 0")));
        data.put("relationships", Long.valueOf(scalar(connection,
                "SELECT COUNT(*) FROM " + openhds + ".`relationship` WHERE deleted = 0")));
        data.put("householdsByKebele", queryCategoryCounts(connection,
                "SELECT COALESCE(kebele, 'Unassigned') AS label, COUNT(DISTINCT houseid) AS total FROM "
                        + openhds + ".`allpopkebele2` WHERE houseid IS NOT NULL GROUP BY kebele ORDER BY kebele"));
        data.put("populationByKebele", queryCategoryCounts(connection,
                "SELECT COALESCE(kebele, 'Unassigned') AS label, COUNT(DISTINCT uuid) AS total FROM "
                        + openhds + ".`allpopkebele` WHERE uuid IS NOT NULL GROUP BY kebele ORDER BY kebele"));
        data.put("populationBySex", queryCategoryCounts(connection,
                "SELECT COALESCE(NULLIF(gender, ''), 'Unspecified') AS label, COUNT(DISTINCT uuid) AS total FROM "
                        + openhds + ".`allpopkebele` WHERE uuid IS NOT NULL GROUP BY gender ORDER BY gender"));
        return data;
    }

    private Map<String, Object> getProgress(Connection connection, int roundNumber) throws SQLException {
        Map<String, Object> progress = new LinkedHashMap<String, Object>();
        long total = scalar(connection,
                "SELECT COUNT(DISTINCT houseid) FROM " + openhds + ".`allpopkebele2` WHERE houseid IS NOT NULL");
        progress.put("totalHouseholds", Long.valueOf(total));
        if (roundNumber == 0) {
            progress.put("tracking", Boolean.FALSE);
            progress.put("visited", null);
            progress.put("notVisited", null);
            progress.put("completionPct", null);
            progress.put("byCluster", new ArrayList<Map<String, Object>>());
            progress.put("byWorker", new ArrayList<Map<String, Object>>());
            return progress;
        }

        long visited = scalar(connection, "SELECT COUNT(*) FROM " + odk + ".`visited`");
        progress.put("tracking", Boolean.TRUE);
        progress.put("visited", Long.valueOf(visited));
        progress.put("notVisited", Long.valueOf(Math.max(0L, total - visited)));
        progress.put("completionPct", Double.valueOf(total == 0L ? 0.0d : 100.0d * visited / total));
        progress.put("byCluster", getClusterProgress(connection, roundNumber));
        progress.put("byWorker", getWorkerProgress(connection, roundNumber));
        return progress;
    }

    private List<Map<String, Object>> getClusterProgress(Connection connection, int roundNumber)
            throws SQLException {
        String sql = "SELECT COALESCE(h.cluster, 'Unassigned') AS label, "
                + "COUNT(*) AS total, "
                + "COUNT(DISTINCT CASE WHEN v.houseId IS NOT NULL THEN h.houseid END) AS visited "
                + "FROM " + baselineHouseholdsSql() + " h "
                + "LEFT JOIN (SELECT DISTINCT OPENHDS_LOCATION_ID AS houseId FROM " + odk
                + ".`visit_registration_core` WHERE OPENHDS_ROUND_NUMBER = ?) v ON v.houseId = h.houseid "
                + "GROUP BY h.cluster ORDER BY h.cluster";
        return queryBreakdown(connection, sql, roundNumber, true);
    }

    private List<Map<String, Object>> getWorkerProgress(Connection connection, int roundNumber)
            throws SQLException {
        String sql = "SELECT COALESCE(NULLIF(v.OPENHDS_FIELD_WORKER_ID, ''), 'Unassigned') AS label, "
                + "COUNT(DISTINCT v.OPENHDS_LOCATION_ID) AS visited "
                + "FROM " + odk + ".`visit_registration_core` v JOIN "
                + baselineHouseholdsSql() + " h "
                + "ON h.houseid = v.OPENHDS_LOCATION_ID "
                + "WHERE v.OPENHDS_ROUND_NUMBER = ? GROUP BY v.OPENHDS_FIELD_WORKER_ID "
                + "ORDER BY visited DESC, label";
        return queryBreakdown(connection, sql, roundNumber, false);
    }

    private List<Map<String, Object>> queryBreakdown(Connection connection, String sql,
                                                     int roundNumber, boolean hasTotal)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setInt(1, roundNumber);
            ResultSet result = statement.executeQuery();
            try {
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("label", result.getString("label"));
                    if (hasTotal) {
                        long total = result.getLong("total");
                        long visited = result.getLong("visited");
                        row.put("total", Long.valueOf(total));
                        row.put("visited", Long.valueOf(visited));
                        row.put("notVisited", Long.valueOf(Math.max(0L, total - visited)));
                        row.put("completionPct", Double.valueOf(total == 0L ? 0.0d : 100.0d * visited / total));
                    } else {
                        row.put("visited", Long.valueOf(result.getLong("visited")));
                    }
                    rows.add(row);
                }
            } finally {
                result.close();
            }
        } finally {
            statement.close();
        }
        return rows;
    }

    private Map<String, Object> getNewBaseline(Connection connection, int roundNumber)
            throws SQLException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (roundNumber == 0) {
            data.put("available", Boolean.FALSE);
            data.put("message", "New baseline submissions are shown for active rounds.");
            data.put("houses", null);
            data.put("population", null);
            data.put("byCluster", new ArrayList<Map<String, Object>>());
            data.put("populationByCluster", new ArrayList<Map<String, Object>>());
            return data;
        }

        String housesSql = "SELECT COUNT(*) FROM " + odk + ".`new_baseline`";
        String populationSql = "SELECT COUNT(*) FROM " + odk + ".`new_population`";
        data.put("available", Boolean.TRUE);
        data.put("houses", Long.valueOf(scalar(connection, housesSql)));
        data.put("population", Long.valueOf(scalar(connection, populationSql)));
        data.put("byCluster", getNewHousesByCluster(connection, roundNumber));
        data.put("populationByCluster", getNewPopulationByCluster(connection, roundNumber));
        return data;
    }

    private List<Map<String, Object>> getNewHousesByCluster(Connection connection, int roundNumber)
            throws SQLException {
        String cluster = "COALESCE(NULLIF(LEFT(OPENHDS_LOCATION_ID, 3), ''), 'Unmapped')";
        String sql = "SELECT " + cluster + " AS label, COUNT(*) AS total FROM "
                + odk + ".`new_baseline` GROUP BY LEFT(OPENHDS_LOCATION_ID, 3) ORDER BY label";
        return queryCategoryCounts(connection, sql);
    }

    private List<Map<String, Object>> getNewPopulationByCluster(Connection connection, int roundNumber)
            throws SQLException {
        String cluster = "COALESCE(NULLIF(LEFT(INDIVIDUAL_INFO_INDIVIDUAL_ID, 3), ''), 'Unmapped')";
        String sql = "SELECT " + cluster + " AS label, COUNT(*) AS total FROM "
                + odk + ".`new_population` GROUP BY LEFT(INDIVIDUAL_INFO_INDIVIDUAL_ID, 3) "
                + "ORDER BY label";
        return queryCategoryCounts(connection, sql);
    }

    private String baselineHouseholdsSql() {
        return "(SELECT houseid, MAX(kebele) AS kebele, MAX(cluster) AS cluster, "
                + "MAX(latitude) AS latitude, MAX(longitude) AS longitude FROM " + openhds
                + ".`allpopkebele2` WHERE houseid IS NOT NULL GROUP BY houseid)";
    }

    private List<Map<String, Object>> getVitalEvents(Connection connection, int roundNumber)
            throws SQLException {
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        events.add(eventCount(connection, roundNumber, "Births", "pregnancy_outcome_core",
                "COUNT(DISTINCT f._URI)", null));
        events.add(eventCount(connection, roundNumber, "Deaths", "death_registration_core",
                "COUNT(DISTINCT f._URI)", null));
        events.add(eventCount(connection, roundNumber, "In-migration", "in_migration_core",
                "COUNT(DISTINCT f._URI)", null));
        events.add(classifiedEvent(connection, roundNumber, "Internal in-migration", "in_migration_core",
                "OPENHDS_MIGRATION_TYPE", "INTERNAL"));
        events.add(classifiedEvent(connection, roundNumber, "External in-migration", "in_migration_core",
                "OPENHDS_MIGRATION_TYPE", "EXTERNAL"));
        events.add(eventCount(connection, roundNumber, "Out-migration", "out_migration_registration_core",
                "COUNT(DISTINCT f._URI)", null));
        events.add(classifiedEvent(connection, roundNumber, "Internal out-migration",
                "out_migration_registration_core", "WITHIN_HDSS_KEBELES", "INTERNAL"));
        events.add(classifiedEvent(connection, roundNumber, "External out-migration",
                "out_migration_registration_core", "WITHIN_HDSS_KEBELES", "EXTERNAL"));
        return events;
    }

    private List<Map<String, Object>> getEventUpdates(Connection connection, int roundNumber)
            throws SQLException {
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        events.add(tableCountEvent(connection, "Immunization", "child_immunization_core"));
        events.add(tableCountEvent(connection, "Family planning", "family_planning_core"));
        events.add(tableCountEvent(connection, "Pregnancy observation", "pregnancy_observation_core"));
        events.add(tableCountEvent(connection, "Child morbidity", "child_morbidity_core"));
        events.add(tableCountEvent(connection, "Adult morbidity", "adult_morbidity_core"));
        events.add(tableCountEvent(connection, "National ID", "national_id_core"));
        events.add(tableCountEvent(connection, "Economic information", "economic_information_core"));
        events.add(tableCountEvent(connection, "Household condition", "house_condition_core"));
        return events;
    }

    private Map<String, Object> tableCountEvent(Connection connection, String label, String table)
            throws SQLException {
        if (!tableExists(connection, config.getOdkSchema(), table)) {
            return unavailableEvent(label, "The " + table + " form table is not present.");
        }
        long count = scalar(connection, "SELECT COUNT(*) FROM " + odk + ".`" + table + "`");
        return availableEvent(label, count, "All table rows (not round-filtered)");
    }

    private Map<String, Object> classifiedEvent(Connection connection, int roundNumber, String label,
                                                String table, String column, String category)
            throws SQLException {
        String predicate;
        if ("INTERNAL".equals(category) && "OPENHDS_MIGRATION_TYPE".equals(column)) {
            predicate = "UPPER(COALESCE(f." + column + ", '')) LIKE '%INTERNAL%'";
        } else if ("EXTERNAL".equals(category) && "OPENHDS_MIGRATION_TYPE".equals(column)) {
            predicate = "UPPER(COALESCE(f." + column + ", '')) LIKE '%EXTERNAL%'";
        } else if ("INTERNAL".equals(category)
                && "WITHIN_HDSS_KEBELES".equals(column)) {
            predicate = "TRIM(COALESCE(f." + column + ", '')) = '1'";
        } else if ("EXTERNAL".equals(category)
                && "WITHIN_HDSS_KEBELES".equals(column)) {
            predicate = "TRIM(COALESCE(f." + column + ", '')) = '2'";
        } else if ("INTERNAL".equals(category)) {
            predicate = "(UPPER(COALESCE(f." + column + ", '')) LIKE '%YES%' "
                    + "OR UPPER(COALESCE(f." + column + ", '')) LIKE '%WITHIN%')";
        } else {
            predicate = "(UPPER(COALESCE(f." + column + ", '')) LIKE '%NO%' "
                    + "OR UPPER(COALESCE(f." + column + ", '')) LIKE '%OUTSIDE%' "
                    + "OR UPPER(COALESCE(f." + column + ", '')) LIKE '%EXTERNAL%')";
        }
        return eventCount(connection, roundNumber, label, table,
                "COUNT(DISTINCT f._URI)", predicate);
    }

    private Map<String, Object> eventCount(Connection connection, int roundNumber, String label,
                                           String table, String aggregate, String predicate)
            throws SQLException {
        if (roundNumber == 0) {
            return unavailableEvent(label, "Not applicable to the baseline round.");
        }
        if (!tableExists(connection, config.getOdkSchema(), table)) {
            return unavailableEvent(label, "The " + table + " form table is not present.");
        }
        if (!columnExists(connection, config.getOdkSchema(), table, "_URI")) {
            return unavailableEvent(label, "The form does not expose a record identifier.");
        }
        if (predicate != null && !columnExists(connection, config.getOdkSchema(), table,
                predicateColumn(predicate))) {
            return unavailableEvent(label, "The form does not expose the field needed to classify this event.");
        }
        String from;
        String where;
        String scope;
        if (columnExists(connection, config.getOdkSchema(), table, "OPENHDS_VISIT_ID")) {
            from = " FROM " + odk + ".`" + table + "` f JOIN " + odk
                    + ".`visit_registration_core` v ON v.OPENHDS_VISIT_ID = f.OPENHDS_VISIT_ID ";
            where = "v.OPENHDS_ROUND_NUMBER = ?";
            scope = "visit-linked";
        } else if (columnExists(connection, config.getOdkSchema(), table, "OPENHDS_LOCATION_ID")) {
            from = " FROM " + odk + ".`" + table + "` f JOIN " + odk
                    + ".`visit_registration_core` v ON v.OPENHDS_LOCATION_ID = f.OPENHDS_LOCATION_ID ";
            where = "v.OPENHDS_ROUND_NUMBER = ?";
            scope = "visit-linked";
        } else if (columnExists(connection, config.getOdkSchema(), table, "_CREATION_DATE")) {
            Date[] dates = getRoundDates(connection, roundNumber);
            if (dates[0] == null || dates[1] == null) {
                return unavailableEvent(label,
                        "No visit ID or round dates are available to scope this form to the selected round.");
            }
            from = " FROM " + odk + ".`" + table + "` f ";
            where = "f._CREATION_DATE >= ? AND f._CREATION_DATE < DATE_ADD(?, INTERVAL 1 DAY)";
            scope = "date-window";
            String sql = "SELECT " + aggregate + from + "WHERE " + where
                    + (predicate == null ? "" : " AND " + predicate);
            return executeDateEventCount(connection, label, sql, dates, scope);
        } else {
            return unavailableEvent(label, "No round linkage is available for this form.");
        }

        String sql = "SELECT " + aggregate + from + "WHERE " + where
                + (predicate == null ? "" : " AND " + predicate);
        return executeEventCount(connection, label, sql, roundNumber, scope);
    }

    private Map<String, Object> executeEventCount(Connection connection, String label,
                                                  String sql, int roundNumber, String scope)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setInt(1, roundNumber);
            ResultSet result = statement.executeQuery();
            try {
                result.next();
                return availableEvent(label, result.getLong(1), scope);
            } finally {
                result.close();
            }
        } finally {
            statement.close();
        }
    }

    private Map<String, Object> executeDateEventCount(Connection connection, String label,
                                                      String sql, Date[] dates, String scope)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setDate(1, dates[0]);
            statement.setDate(2, dates[1]);
            ResultSet result = statement.executeQuery();
            try {
                result.next();
                return availableEvent(label, result.getLong(1), scope);
            } finally {
                result.close();
            }
        } finally {
            statement.close();
        }
    }

    private Map<String, Object> getRoundInfo(Connection connection, int roundNumber)
            throws SQLException {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("number", Integer.valueOf(roundNumber));
        info.put("label", roundNumber == 0 ? "Baseline (Round 0)" : "Round " + roundNumber);
        if (roundNumber > 0) {
            String sql = "SELECT startDate, endDate FROM " + openhds
                    + ".`round` WHERE roundNumber = ?";
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                statement.setInt(1, roundNumber);
                ResultSet result = statement.executeQuery();
                try {
                    if (result.next()) {
                        info.put("startDate", result.getString("startDate"));
                        info.put("endDate", result.getString("endDate"));
                    } else {
                        info.put("startDate", null);
                        info.put("endDate", null);
                    }
                } finally {
                    result.close();
                }
            } finally {
                statement.close();
            }
        } else {
            info.put("startDate", null);
            info.put("endDate", null);
        }
        return info;
    }

    private Date[] getRoundDates(Connection connection, int roundNumber) throws SQLException {
        Date[] dates = new Date[2];
        String sql = "SELECT startDate, endDate FROM " + openhds
                + ".`round` WHERE roundNumber = ?";
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setInt(1, roundNumber);
            ResultSet result = statement.executeQuery();
            try {
                if (result.next()) {
                    dates[0] = result.getDate("startDate");
                    dates[1] = result.getDate("endDate");
                }
            } finally {
                result.close();
            }
        } finally {
            statement.close();
        }
        return dates;
    }

    private List<Map<String, Object>> queryCountBreakdown(Connection connection, String sql,
                                                          int roundNumber, String valueColumn)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setInt(1, roundNumber);
            ResultSet result = statement.executeQuery();
            try {
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("label", result.getString("label"));
                    row.put(valueColumn, Long.valueOf(result.getLong(valueColumn)));
                    rows.add(row);
                }
            } finally {
                result.close();
            }
        } finally {
            statement.close();
        }
        return rows;
    }

    private List<Map<String, Object>> queryCategoryCounts(Connection connection, String sql)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Statement statement = connection.createStatement();
        try {
            ResultSet result = statement.executeQuery(sql);
            try {
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("label", result.getString("label"));
                    row.put("total", Long.valueOf(result.getLong("total")));
                    rows.add(row);
                }
            } finally {
                result.close();
            }
        } finally {
            statement.close();
        }
        return rows;
    }

    private long scalar(Connection connection, String sql) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            ResultSet result = statement.executeQuery(sql);
            try {
                result.next();
                return result.getLong(1);
            } finally {
                result.close();
            }
        } finally {
            statement.close();
        }
    }

    private long scalar(Connection connection, String sql, int parameter) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setInt(1, parameter);
            ResultSet result = statement.executeQuery();
            try {
                result.next();
                return result.getLong(1);
            } finally {
                result.close();
            }
        } finally {
            statement.close();
        }
    }

    private boolean tableExists(Connection connection, String schema, String table)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        ResultSet result = metadata.getTables(schema, null, table, new String[] {"TABLE", "VIEW"});
        try {
            return result.next();
        } finally {
            result.close();
        }
    }

    private boolean columnExists(Connection connection, String schema, String table, String column)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        ResultSet result = metadata.getColumns(schema, null, table, column);
        try {
            return result.next();
        } finally {
            result.close();
        }
    }

    private Map<String, Object> availableEvent(String label, long count, String scope) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("label", label);
        event.put("count", Long.valueOf(count));
        event.put("available", Boolean.TRUE);
        event.put("scope", scope);
        event.put("message", null);
        return event;
    }

    private Map<String, Object> unavailableEvent(String label, String message) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("label", label);
        event.put("count", null);
        event.put("available", Boolean.FALSE);
        event.put("scope", null);
        event.put("message", message);
        return event;
    }

    private String predicateColumn(String predicate) {
        int start = predicate.indexOf("f.");
        if (start < 0) {
            return "";
        }
        int end = start + 2;
        while (end < predicate.length()) {
            char c = predicate.charAt(end);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                break;
            }
            end++;
        }
        return predicate.substring(start + 2, end);
    }

    private String quoteSchema(String schema) {
        return "`" + schema + "`";
    }

    private Number parseNumber(String value) {
        if (value == null || value.trim().length() == 0) {
            return null;
        }
        try {
            return Double.valueOf(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> asMap(Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }
}
