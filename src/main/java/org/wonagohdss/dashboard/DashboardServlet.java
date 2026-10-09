package org.wonagohdss.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class DashboardServlet extends HttpServlet {
    private static final Logger LOGGER = Logger.getLogger(DashboardServlet.class.getName());
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String SESSION_CONFIG = DashboardServlet.class.getName() + ".databaseConfig";

    private DashboardRepository defaultRepository;
    private DatabaseConfig defaultConfig;

    public void init() throws ServletException {
        try {
            defaultConfig = DatabaseConfig.fromEnvironment();
            defaultRepository = new DashboardRepository(defaultConfig);
        } catch (IllegalArgumentException e) {
            throw new ServletException("Invalid Round Monitor database configuration.", e);
        }
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");
        String path = request.getPathInfo();
        if (path == null) {
            path = "/";
        }
        try {
            if ("/settings".equals(path)) {
                Object sessionConfig = request.getSession(false) == null ? null
                        : request.getSession(false).getAttribute(SESSION_CONFIG);
                DatabaseConfig config = sessionConfig instanceof DatabaseConfig
                        ? (DatabaseConfig) sessionConfig : defaultConfig;
                writeJson(response, HttpServletResponse.SC_OK, config.getPublicSettings());
            } else if ("/rounds".equals(path)) {
                writeJson(response, HttpServletResponse.SC_OK, repositoryFor(request).getRounds());
            } else if ("/dashboard".equals(path)) {
                int round = readRound(request, response);
                if (round >= 0) {
                    writeJson(response, HttpServletResponse.SC_OK, repositoryFor(request).getDashboard(round));
                }
            } else if ("/field-worker-submissions".equals(path)) {
                writeFieldWorkerSubmissions(request, response);
            } else if ("/not-visited".equals(path)) {
                int round = readRound(request, response);
                if (round >= 0) {
                    writeJson(response, HttpServletResponse.SC_OK, repositoryFor(request).getNotVisited(round));
                }
            } else if ("/not-visited.csv".equals(path)) {
                writeNotVisitedCsv(request, response);
            } else if ("/locations".equals(path)) {
                int round = readRound(request, response);
                if (round >= 0) {
                    writeJson(response, HttpServletResponse.SC_OK, repositoryFor(request).getLocations(round));
                }
            } else if ("/export.csv".equals(path)) {
                writeCsv(request, response);
            } else {
                writeError(response, HttpServletResponse.SC_NOT_FOUND, "API endpoint not found.");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Round Monitor database request failed.", e);
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Database request failed. Check the read-only database connection and schema configuration.");
        }
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");
        if (!"/settings".equals(request.getPathInfo())) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "API endpoint not found.");
            return;
        }
        DatabaseConfig candidate;
        try {
            candidate = DatabaseConfig.fromSettings(
                    request.getParameter("host"),
                    request.getParameter("port"),
                    request.getParameter("openhdsSchema"),
                    request.getParameter("odkSchema"),
                    request.getParameter("user"),
                    request.getParameter("password"));
        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }

        try {
            testConnection(candidate);
            request.getSession(true).setAttribute(SESSION_CONFIG, candidate);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("ok", Boolean.TRUE);
            result.put("message", "Connection successful. OpenHDS and ODK access verified for this session.");
            result.put("settings", candidate.getPublicSettings());
            writeJson(response, HttpServletResponse.SC_OK, result);
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Database settings connection test failed.", e);
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Connection test failed: " + safeMessage(e));
        }
    }

    private DashboardRepository repositoryFor(HttpServletRequest request) {
        Object sessionConfig = request.getSession(false) == null ? null
                : request.getSession(false).getAttribute(SESSION_CONFIG);
        if (sessionConfig instanceof DatabaseConfig) {
            return new DashboardRepository((DatabaseConfig) sessionConfig);
        }
        return defaultRepository;
    }

    private void testConnection(DatabaseConfig config) throws SQLException {
        Connection connection = config.openConnection();
        try {
            verifyRead(connection, config.getOpenhdsSchema(), "round");
            verifyRead(connection, config.getOpenhdsSchema(), "allpopkebele2");
            verifyRead(connection, config.getOpenhdsSchema(), "allpopkebele");
            verifyRead(connection, config.getOdkSchema(), "visit_registration_core");
            verifyRead(connection, config.getOdkSchema(), "visited");
            verifyRead(connection, config.getOdkSchema(), "new_baseline");
            verifyRead(connection, config.getOdkSchema(), "new_population");
            verifyRead(connection, config.getOdkSchema(), "economic_information_core");
            verifyRead(connection, config.getOdkSchema(), "house_condition_core");
            String crossSchemaSql = "SELECT h.houseid FROM `" + config.getOpenhdsSchema()
                    + "`.`allpopkebele2` h LEFT JOIN `" + config.getOdkSchema()
                    + "`.`visit_registration_core` v ON v.OPENHDS_LOCATION_ID = h.houseid LIMIT 0";
            executeRead(connection, crossSchemaSql);
        } finally {
            connection.close();
        }
    }

    private void verifyRead(Connection connection, String schema, String table) throws SQLException {
        executeRead(connection, "SELECT * FROM `" + schema + "`.`" + table + "` LIMIT 0");
    }

    private void executeRead(Connection connection, String sql) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.executeQuery(sql).close();
        } finally {
            statement.close();
        }
    }

    private String safeMessage(SQLException e) {
        String message = e.getMessage();
        if (message == null || message.trim().length() == 0) {
            return "check the host, port, credentials, schema names and required table access.";
        }
        return message.replaceAll("(?i)(password\\s*[=:]\\s*)[^\\s,;]+", "$1[hidden]");
    }

    private int readRound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String value = request.getParameter("round");
        try {
            int round = Integer.parseInt(value);
            if (round < 0) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Round must be zero or greater.");
                return -1;
            }
            return round;
        } catch (NumberFormatException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "A valid round number is required.");
            return -1;
        }
    }

    private void writeFieldWorkerSubmissions(HttpServletRequest request, HttpServletResponse response)
            throws SQLException, IOException {
        int round = readRound(request, response);
        if (round < 0) {
            return;
        }
        String type = request.getParameter("type");
        if (!"baseline".equals(type) && !"visit".equals(type)) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Submission type must be baseline or visit.");
            return;
        }
        String fromValue = request.getParameter("from");
        String toValue = request.getParameter("to");
        boolean hasFrom = fromValue != null && fromValue.trim().length() > 0;
        boolean hasTo = toValue != null && toValue.trim().length() > 0;
        if (hasFrom != hasTo) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Both start and end dates are required for a custom interval.");
            return;
        }

        java.sql.Date from = null;
        java.sql.Date to = null;
        if (hasFrom) {
            if (!fromValue.trim().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || !toValue.trim().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Dates must use the YYYY-MM-DD format.");
                return;
            }
            try {
                from = java.sql.Date.valueOf(fromValue.trim());
                to = java.sql.Date.valueOf(toValue.trim());
            } catch (IllegalArgumentException e) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Dates must use the YYYY-MM-DD format.");
                return;
            }
            if (from.after(to)) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "The start date must not be after the end date.");
                return;
            }
        }
        List<Map<String, Object>> rows = repositoryFor(request)
                .getFieldWorkerSubmissions(type, round, from, to);
        writeJson(response, HttpServletResponse.SC_OK, rows);
    }

    private void writeCsv(HttpServletRequest request, HttpServletResponse response)
            throws SQLException, IOException {
        int round = readRound(request, response);
        if (round < 0) {
            return;
        }
        String dimension = "worker".equals(request.getParameter("by")) ? "worker" : "cluster";
        List<Map<String, Object>> rows = repositoryFor(request).getExportRows(round, dimension);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"round-" + round + "-" + dimension + "-progress.csv\"");
        response.getWriter().write("worker".equals(dimension)
                ? "Field worker,Unique baseline households visited\r\n"
                : "Cluster,Total households,Visited,Not visited,Completion percent\r\n");
        for (Map<String, Object> row : rows) {
            Object total = row.get("total");
            Object visited = row.get("visited");
            Object notVisited = row.get("notVisited");
            Object completion = row.get("completionPct");
            if ("worker".equals(dimension)) {
                response.getWriter().write(csv(row.get("label")) + "," + csv(visited) + "\r\n");
            } else {
                response.getWriter().write(csv(row.get("label")) + ","
                        + csv(total) + "," + csv(visited) + "," + csv(notVisited) + ","
                        + csv(completion) + "\r\n");
            }
        }
    }

    private void writeNotVisitedCsv(HttpServletRequest request, HttpServletResponse response)
            throws SQLException, IOException {
        int round = readRound(request, response);
        if (round < 0) {
            return;
        }
        List<Map<String, Object>> rows = repositoryFor(request).getNotVisited(round);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"round-" + round + "-not-visited-households.csv\"");
        response.getWriter().write("Round,Kebele,Cluster,Household ID\r\n");
        for (Map<String, Object> row : rows) {
            response.getWriter().write(csv(Integer.valueOf(round)) + ","
                    + csv(row.get("kebele")) + ","
                    + csv(row.get("cluster")) + ","
                    + csv(row.get("houseId")) + "\r\n");
        }
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(GSON.toJson(body));
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        Map<String, String> error = new java.util.LinkedHashMap<String, String>();
        error.put("error", message);
        writeJson(response, status, error);
    }
}
