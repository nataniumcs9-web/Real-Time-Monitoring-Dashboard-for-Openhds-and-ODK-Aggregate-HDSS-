package org.wonagohdss.dashboard;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class DatabaseConfig {
    private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern HOST_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]*|\\[[0-9A-Fa-f:]+\\]");

    private final String url;
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String openhdsSchema;
    private final String odkSchema;

    private DatabaseConfig(String host, int port, String url, String user, String password,
                           String openhdsSchema, String odkSchema) {
        String configuredHost = host == null ? "" : host.trim();
        this.host = configuredHost.length() == 0 ? "" : validateHost(configuredHost);
        this.port = validatePort(port);
        this.url = url;
        String configuredUser = user == null ? "" : user.trim();
        this.user = configuredUser.length() == 0 ? "" : validateUser(configuredUser);
        this.password = password;
        this.openhdsSchema = validateSchema(openhdsSchema, "openhds");
        this.odkSchema = validateSchema(odkSchema, "odk_prod");
    }

    public static DatabaseConfig fromEnvironment() {
        String url = setting("dashboard.db.url", "DASHBOARD_DB_URL");
        String user = setting("dashboard.db.user", "DASHBOARD_DB_USER");
        String password = setting("dashboard.db.password", "DASHBOARD_DB_PASSWORD");
        String openhdsSchema = setting("dashboard.db.openhdsSchema", "DASHBOARD_OPENHDS_SCHEMA");
        String odkSchema = setting("dashboard.db.odkSchema", "DASHBOARD_ODK_SCHEMA");
        String host = setting("dashboard.db.host", "DASHBOARD_DB_HOST");
        if (host == null || host.trim().length() == 0) {
            host = hostFromUrl(url);
        }
        if (host == null || host.trim().length() == 0) {
            host = "localhost";
        }
        String configuredPort = setting("dashboard.db.port", "DASHBOARD_DB_PORT");
        int port = parsePort(configuredPort, url);
        String safeOpenhds = validateSchema(openhdsSchema, "openhds");
        String configuredUrl = url == null || url.trim().length() == 0
                ? "jdbc:mysql://" + validateHost(host) + ":" + port + "/" + safeOpenhds
                    + "?useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=convertToNull"
                : url;
        String configuredUser = user == null || user.trim().length() == 0 ? "whdss" : user;
        return new DatabaseConfig(host, port, configuredUrl,
                configuredUser, password, safeOpenhds, odkSchema);
    }

    public static DatabaseConfig fromSettings(String host, String port, String openhdsSchema,
                                              String odkSchema, String user, String password) {
        int parsedPort;
        try {
            parsedPort = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number from 1 to 65535.");
        }
        String safeOpenhds = validateSchema(openhdsSchema, "openhds");
        String url = "jdbc:mysql://" + validateHost(host) + ":" + validatePort(parsedPort)
                + "/" + safeOpenhds
                + "?useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=convertToNull";
        return new DatabaseConfig(validateHost(host), parsedPort, url, validateUser(user), password,
                safeOpenhds, odkSchema);
    }

    private static int parsePort(String configuredPort, String url) {
        if (configuredPort != null && configuredPort.trim().length() > 0) {
            try {
                return validatePort(Integer.parseInt(configuredPort.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Port must be a number from 1 to 65535.", e);
            }
        }
        if (url != null && url.startsWith("jdbc:")) {
            try {
                int urlPort = new URI(url.substring("jdbc:".length())).getPort();
                if (urlPort > 0) {
                    return validatePort(urlPort);
                }
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("DASHBOARD_DB_URL is invalid.", e);
            }
        }
        return 3306;
    }

    private static String hostFromUrl(String url) {
        if (url == null || !url.startsWith("jdbc:")) {
            return "";
        }
        try {
            String host = new URI(url.substring("jdbc:".length())).getHost();
            return host == null ? "" : host;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("DASHBOARD_DB_URL is invalid.", e);
        }
    }

    public Connection openConnection() throws SQLException {
        if (url == null || url.trim().length() == 0) {
            throw new SQLException("DASHBOARD_DB_URL is not configured.");
        }
        if (user == null || user.trim().length() == 0) {
            throw new SQLException("DASHBOARD_DB_USER is not configured.");
        }
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver is not available.", e);
        }
        Connection connection = DriverManager.getConnection(url, user, password == null ? "" : password);
        try {
            Statement statement = connection.createStatement();
            try {
                statement.execute("SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY', '')");
            } finally {
                statement.close();
            }
            return connection;
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw new SQLException("Could not prepare the MySQL session for the supplied OpenHDS views.", e);
        }
    }

    public String getOpenhdsSchema() {
        return openhdsSchema;
    }

    public String getOdkSchema() {
        return odkSchema;
    }

    public Map<String, Object> getPublicSettings() {
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("host", host);
        settings.put("port", Integer.valueOf(port));
        settings.put("openhdsSchema", openhdsSchema);
        settings.put("odkSchema", odkSchema);
        settings.put("user", user);
        settings.put("passwordConfigured", Boolean.valueOf(password != null && password.length() > 0));
        return settings;
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.length() == 0) {
            value = System.getenv(environment);
        }
        return value;
    }

    private static String validateSchema(String value, String defaultValue) {
        String schema = value == null || value.trim().length() == 0 ? defaultValue : value.trim();
        if (!SCHEMA_NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid database schema name: " + schema);
        }
        return schema;
    }

    private static String validateHost(String value) {
        String host = value == null ? "" : value.trim();
        if (!HOST_NAME.matcher(host).matches()) {
            throw new IllegalArgumentException("Enter a valid database host name or IP address.");
        }
        return host;
    }

    private static int validatePort(int value) {
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException("Port must be a number from 1 to 65535.");
        }
        return value;
    }

    private static String validateUser(String value) {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException("Database user is required.");
        }
        return value.trim();
    }
}
