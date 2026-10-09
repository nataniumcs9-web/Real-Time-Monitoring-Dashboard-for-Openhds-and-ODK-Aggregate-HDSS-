# Wonago HDSS Round Monitor

**Version 1.0.0**

Copyright (c) 2026 Natnael Abebe. Distributed under the MIT License; see [LICENSE](LICENSE).

A read-only Java web application for monitoring OpenHDS baseline denominators and ODK round activity. It packages as a WAR and uses the Java EE 5 / Servlet 2.5 API for Tomcat 6. The responsive monitoring dashboard includes baseline household/population distribution by Kebele and sex, household coverage by cluster and field worker, a not-visited follow-up list with CSV and print-to-PDF export, a clustered household map filterable by Kebele or cluster, round-specific registration and event counts, a printable report, a Word-compatible HTML export, and a 16:9 presentation view.

## Version 1 release

The v1.0.0 release is a Tomcat 6-compatible WAR built from this source. Download `round-monitor.war` from the GitHub Release assets, then follow the build and deploy instructions below. No database dump, production credential, or populated local configuration file is required or included.

## Stack and compatibility

- Java 7 bytecode and Servlet 2.5 (`javax.servlet`), suitable for Tomcat 6 on a Java 7 runtime.
- MySQL Connector/J 5.1.49 for the MySQL 5.6-era schemas in the supplied SQL dump.
- No database writes are issued by the application.
- The map uses Leaflet, MarkerCluster, and OpenStreetMap tiles from public CDNs; report charts and summary tables are generated locally.

## Database configuration

The application connects to the MySQL server configured by the user or deployment environment; it does not contain production counts or database credentials. The settings dialog pre-fills only the schema names (`openhds`, `odk_prod`) and standard MySQL port. Enter the actual production host, database account, and password, then choose **Test connection & apply**. Local backup credentials are not prefilled. A successful test verifies read access to the OpenHDS round and baseline views, the ODK visit and registration tables, and a cross-schema join. It applies the connection to the current server-side session. Alternatively, set these environment variables for Tomcat before starting it:

```text
DASHBOARD_DB_URL=jdbc:mysql://db-host:3306/openhds?useUnicode=true&characterEncoding=UTF-8
DASHBOARD_DB_HOST=db-host
DASHBOARD_DB_PORT=3306
DASHBOARD_DB_USER=round_monitor_readonly
DASHBOARD_DB_PASSWORD=your-read-only-password
DASHBOARD_OPENHDS_SCHEMA=openhds
DASHBOARD_ODK_SCHEMA=odk_prod
```

`dashboard.properties.example` shows the same names. The configured user should have `SELECT` access only to:

- `openhds.allpopkebele2`, `openhds.allpopkebele`, `openhds.socialgroup`, `openhds.relationship`, and `openhds.round`
- The ODK tables used by this dashboard in `odk_prod`, especially `visit_registration_core`, `location_registration_core`, `baseline_core`, vital-event forms, morbidity forms, immunization, family planning, economic information, and national-ID forms.

The schema names can be changed with the corresponding environment variables. The connection URL, user, and password are not stored in the WAR or source tree. Passwords entered in the settings dialog are not stored in browser storage or returned by the settings API; successfully tested settings are held in server memory for the browser's HTTP session for up to eight hours, and must be entered again after that session expires or Tomcat restarts. If the saved database session is unavailable, the dashboard opens connection settings instead of leaving the round selector on “Loading rounds…”. Use the production server and a dedicated least-privilege read-only account. The map and follow-up table include sensitive household location/ID information; deploy behind the organization’s authentication and HTTPS controls and do not expose this app to a public network.

Dashboard baseline, progress, registration, and event sections load concurrently over separate read-only connections. On the configured live database, all five sections completed in about 5 seconds in a direct benchmark; timings vary with database load.

On each JDBC connection, the app removes `ONLY_FULL_GROUP_BY` from that connection's session `sql_mode` only. This is required by the supplied OpenHDS baseline views, whose grouped selects fail under that mode; other SQL modes are preserved. No global server mode is changed.

## Build and deploy

Build with Maven and a JDK that supports Java 7 compilation:

```text
mvn clean package
```

Copy `target/round-monitor.war` to Tomcat 6's `webapps` directory, configure the environment variables above for the Tomcat service, and restart Tomcat. Open:

```text
http://localhost:8080/round-monitor/
```

The web pages load map and font assets from external CDNs; provide internet access to those hosts or replace the CDN URLs with locally hosted assets for an offline deployment.

## Round and metric behavior

- Round 0 is always available as the baseline. Baseline totals use distinct `allpopkebele2.houseid` and `allpopkebele.uuid` values; social groups and relationships exclude OpenHDS records marked deleted.
- Active round choices come from `openhds.round.roundNumber`. The household-visit progress total counts rows in `odk_prod.visited`; cluster and field-worker breakdowns use `visit_registration_core.OPENHDS_ROUND_NUMBER` and baseline household matching.
- A baseline household is counted once overall and assigned to one baseline cluster for progress, not once per duplicate view row. Field-worker progress is computed from distinct baseline household IDs matched to visits assigned to each field worker in the selected round.
- New households and new population count all rows in `odk_prod.new_baseline` and `odk_prod.new_population`. Their cluster summaries group by the first three characters of `OPENHDS_LOCATION_ID` and `INDIVIDUAL_INFO_INDIVIDUAL_ID`, respectively.
- Event forms with `OPENHDS_VISIT_ID` are scoped to the selected round through `visit_registration_core`. A form without a visit ID may be scoped to `openhds.round` start/end dates and is explicitly labeled `date-window`. Forms that cannot be linked are marked unavailable instead of being shown as zero.
- Births, deaths, in-migrations, and out-migrations are counted from their corresponding ODK core forms. Internal/external in-migration uses `OPENHDS_MIGRATION_TYPE`; internal/external out-migration uses the ODK `WITHIN_HDSS_KEBELES` response codes.
- Event updates include child immunization, family planning, pregnancy observation, child and adult morbidity, national ID, `economic_information_core`, and `house_condition_core`. Each metric is a direct `COUNT(*)` of its corresponding ODK table, without round linkage or filtering.
- The interactive dashboard map uses distinct household IDs, kebele, cluster, and coordinates from the OpenHDS `allpopkebele2` baseline view. Round visit status is matched by `houseid = visit_registration_core.OPENHDS_LOCATION_ID`; it supports marker clustering, status filters, and Kebele/cluster selection. The Word-compatible document omits the map; use the dashboard for interactive mapping.
- The report includes operational monitoring sections based on the provided Wonago-HDSS baseline-report format: baseline summary, round fieldwork and coverage, new registrations, vital events, household/event updates, data sources and limitations. It does not hard-code the sample report's historical counts.

## API

- `GET /api/rounds`
- `GET /api/settings` (returns connection settings without the password)
- `POST /api/settings` (tests a connection and applies it to the current server session)
- `GET /api/dashboard?round=1`
- `GET /api/not-visited?round=1`
- `GET /api/not-visited.csv?round=1`
- `GET /api/locations?round=1`
- `GET /api/export.csv?round=1&by=cluster` (or `by=worker`)

Database failures return HTTP 503 with an actionable configuration message and are logged by the servlet. Invalid round parameters return HTTP 400.

## License

This project’s original source is licensed under the MIT License. Third-party libraries and map data retain their respective licenses and terms.
