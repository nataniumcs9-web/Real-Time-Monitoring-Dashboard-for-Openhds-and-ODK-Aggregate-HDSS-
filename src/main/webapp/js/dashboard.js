(function () {
    "use strict";

    var roundSelect = document.getElementById("round-select");
    var currentDashboard = null;
    var missedRows = [];
    var missedRowsRound = null;
    var map = null;
    var mapGroups = {};
    var mapFilter = "all";
    var mapRows = [];
    var missedLimit = 60;
    var progressMode = "cluster";
    var workerSubmissionMode = "visit";
    var workerSubmissionRange = "15days";
    var workerSubmissionRequest = 0;
    var mapArea = "all";
    var mapAreaValue = "all";
    var navigationLinks = Array.prototype.slice.call(
        document.querySelectorAll("#sidebar-navigation a"));
    var navigationFrame = null;

    function byId(id) {
        return document.getElementById(id);
    }

    function number(value) {
        if (value === null || value === undefined || isNaN(Number(value))) {
            return "—";
        }
        return Number(value).toLocaleString("en-US");
    }

    function escapeHtml(value) {
        return String(value === null || value === undefined ? "" : value)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
    }

    function requestJson(url, options) {
        options = options || {};
        options.headers = options.headers || {};
        options.headers.Accept = "application/json";
        return fetch(url, options).then(function (response) {
            return response.json().then(function (body) {
                if (!response.ok) {
                    throw new Error(body.error || "Request failed.");
                }
                return body;
            });
        });
    }

    function showSettingsStatus(message, state) {
        var status = byId("settings-status");
        status.className = "settings-status " + state;
        status.textContent = message;
        status.hidden = false;
    }

    function openSettings() {
        byId("settings-overlay").hidden = false;
        byId("settings-status").hidden = true;
        byId("db-password").value = "";
        requestJson("api/settings").then(function (settings) {
            byId("db-host").value = settings.host || "";
            byId("db-port").value = settings.port || 3306;
            byId("db-openhds").value = settings.openhdsSchema || "openhds";
            byId("db-odk").value = settings.odkSchema || "odk_prod";
            byId("db-user").value = settings.user || "";
            if (settings.passwordConfigured) {
                showSettingsStatus("A password is already held securely for this session. Enter it again only if you want to change it.", "pending");
            }
            byId("db-host").focus();
        }).catch(function (error) {
            showSettingsStatus("Could not load saved settings: " + error.message, "error");
        });
    }

    function closeSettings() {
        byId("settings-overlay").hidden = true;
        byId("db-password").value = "";
    }

    function testSettings(event) {
        event.preventDefault();
        var button = byId("test-settings");
        var label = byId("test-settings-label");
        var params = new URLSearchParams();
        params.set("host", byId("db-host").value.trim());
        params.set("port", byId("db-port").value);
        params.set("openhdsSchema", byId("db-openhds").value.trim());
        params.set("odkSchema", byId("db-odk").value.trim());
        params.set("user", byId("db-user").value.trim());
        params.set("password", byId("db-password").value);
        button.disabled = true;
        label.textContent = "Testing connection…";
        showSettingsStatus("Connecting to MySQL and checking read access to both schemas…", "pending");
        requestJson("api/settings", {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
            body: params.toString()
        }).then(function (result) {
            showSettingsStatus(result.message, "success");
            byId("db-password").value = "";
            var status = document.querySelector(".privacy-note");
            if (status) { status.innerHTML = '<span class="privacy-dot"></span>Database connected'; }
            window.setTimeout(function () {
                closeSettings();
                byId("connection-error").hidden = true;
                requestJson("api/rounds").then(renderRounds).catch(showRoundsError);
            }, 900);
        }).catch(function (error) {
            showSettingsStatus(error.message, "error");
        }).then(function () {
            button.disabled = false;
            label.textContent = "Test connection & apply";
        });
    }

    function currentRound() {
        return Number(roundSelect.value || 0);
    }

    function updateActiveNavigation() {
        var marker = window.pageYOffset + Math.min(window.innerHeight * 0.32, 220);
        if (window.innerHeight + window.pageYOffset
                >= document.documentElement.scrollHeight - 2) {
            marker = document.documentElement.scrollHeight;
        }
        var activeLink = navigationLinks[0];
        navigationLinks.forEach(function (link) {
            var section = document.getElementById(link.hash.substring(1));
            if (section && section.getBoundingClientRect().top + window.pageYOffset <= marker) {
                activeLink = link;
            }
        });
        navigationLinks.forEach(function (link) {
            var active = link === activeLink;
            link.classList.toggle("active", active);
            if (active) {
                link.setAttribute("aria-current", "location");
            } else {
                link.removeAttribute("aria-current");
            }
        });
    }

    function scheduleActiveNavigation() {
        if (navigationFrame !== null) {
            return;
        }
        navigationFrame = window.requestAnimationFrame(function () {
            navigationFrame = null;
            updateActiveNavigation();
        });
    }

    function updateMissedExportLink() {
        byId("export-missed-csv").href = "api/not-visited.csv?round=" + currentRound();
    }

    function showError(message) {
        byId("loading-state").hidden = true;
        byId("connection-error").textContent = message;
        byId("connection-error").hidden = false;
    }

    function showRoundsError(error) {
        roundSelect.innerHTML = "";
        var option = document.createElement("option");
        option.value = "";
        option.textContent = "Database connection required";
        roundSelect.appendChild(option);
        showError(error.message);
        openSettings();
    }

    function renderRounds(rounds) {
        roundSelect.innerHTML = "";
        rounds.forEach(function (round) {
            var option = document.createElement("option");
            option.value = round.number;
            option.textContent = round.label + (round.startDate ? " · " + round.startDate : "");
            roundSelect.appendChild(option);
        });
        var active = rounds.filter(function (round) { return Number(round.number) > 0; });
        roundSelect.value = active.length ? active[active.length - 1].number : 0;
        loadDashboard();
    }

    function renderBaseline(data) {
        byId("baseline-households").textContent = number(data.households);
        byId("baseline-population").textContent = number(data.population);
        byId("baseline-groups").textContent = number(data.socialGroups);
        byId("baseline-relationships").textContent = number(data.relationships);
        renderMiniBars("baseline-kebele-chart", data.householdsByKebele, "#4b98b1", "total");
        renderMiniBars("baseline-sex-chart", data.populationBySex, "#8273bf", "total");
    }

    function renderDataSummary(roundSummary, round) {
        byId("round-summary-number").textContent = round.number;
        byId("summary-social-groups").textContent = number(roundSummary.socialGroups);
        byId("summary-relationships").textContent = number(roundSummary.relationships);
        byId("summary-membership").textContent = number(roundSummary.membership);
    }

    function renderProgress(progress, round) {
        byId("round-number-label").textContent = round.number === 0 ? "0 · BASELINE" : round.number;
        byId("round-dates").textContent = round.startDate
            ? "Round dates: " + round.startDate + " to " + (round.endDate || "not set")
            : "Coverage is matched by household ID (houseid = OPENHDS_LOCATION_ID) and ODK round number.";
        byId("coverage-total").textContent = number(progress.totalHouseholds);
        if (!progress.tracking) {
            byId("visited-count").textContent = "—";
            byId("not-visited-count").textContent = "—";
            byId("visited-percent").textContent = "—";
            byId("progress-caption").textContent = "Baseline household denominator";
            byId("progress-fill").style.width = "0%";
            byId("baseline-progress-note").hidden = false;
            byId("missed-total").textContent = "—";
        } else {
            byId("visited-count").textContent = number(progress.visited);
            byId("not-visited-count").textContent = number(progress.notVisited);
            byId("visited-percent").textContent = Number(progress.completionPct || 0).toFixed(1) + "%";
            byId("progress-caption").textContent = Number(progress.completionPct || 0).toFixed(1) + "% of baseline households reached";
            byId("progress-fill").style.width = Math.min(100, Math.max(0, Number(progress.completionPct || 0))) + "%";
            byId("baseline-progress-note").hidden = true;
            byId("missed-total").textContent = number(progress.notVisited);
        }
        renderProgressBreakdown();
        updateExportLink();
    }

    function renderProgressBreakdown() {
        if (!currentDashboard) {
            return;
        }
        var progress = currentDashboard.progress;
        var rows = progressMode === "worker" ? progress.byWorker : progress.byCluster;
        var total = Math.max(1, Number(progress.totalHouseholds || 0));
        var html = "";
        rows.forEach(function (row) {
            var visited = Number(row.visited || 0);
            var overall = progressMode === "worker" ? visited / total * 100 : Number(row.completionPct || 0);
            var missed = progressMode === "cluster" ? Number(row.notVisited || 0) : 0;
            html += '<div class="breakdown-row"><span class="breakdown-name" title="' + escapeHtml(row.label) + '">' + escapeHtml(row.label) + '</span>'
                + '<span class="bar-stack"><span class="visited-segment" style="width:' + Math.min(100, overall) + '%"></span>'
                + (progressMode === "cluster" ? '<span class="missed-segment" style="width:' + Math.max(0, 100 - overall) + '%"></span>' : '')
                + '</span><span class="breakdown-value">' + number(visited) + ' visited'
                + (progressMode === "cluster" ? ' · ' + number(missed) + ' left' : '') + '</span></div>';
        });
        byId("progress-breakdown").innerHTML = html || '<div class="inline-note">No visit records are available for this round.</div>';
    }

    function formatDateInput(date) {
        var year = date.getFullYear();
        var month = String(date.getMonth() + 1);
        var day = String(date.getDate());
        return year + "-" + (month.length < 2 ? "0" : "") + month
            + "-" + (day.length < 2 ? "0" : "") + day;
    }

    function setWorkerDateRange(range, shouldLoad) {
        workerSubmissionRange = range;
        var from = byId("worker-submission-from");
        var to = byId("worker-submission-to");
        var today = new Date();
        today.setHours(0, 0, 0, 0);
        if (range === "all") {
            from.value = "";
            to.value = "";
        } else {
            var start = new Date(today.getTime());
            if (range === "week") {
                start.setDate(start.getDate() - (start.getDay() + 6) % 7);
            } else if (range === "15days") {
                start.setDate(start.getDate() - 14);
            } else if (range === "month") {
                start.setDate(1);
            }
            from.value = formatDateInput(start);
            to.value = formatDateInput(today);
        }
        document.querySelectorAll("[data-worker-range]").forEach(function (button) {
            var active = button.getAttribute("data-worker-range") === range;
            button.classList.toggle("active", active);
            button.setAttribute("aria-pressed", String(active));
        });
        byId("worker-date-error").hidden = true;
        if (shouldLoad) {
            loadWorkerSubmissions();
        }
    }

    function workerDateLabel() {
        if (workerSubmissionRange === "all") {
            return "All dates";
        }
        return "Submissions " + byId("worker-submission-from").value
            + " to " + byId("worker-submission-to").value;
    }

    function loadWorkerSubmissions() {
        if (!currentDashboard) {
            return;
        }
        var round = Number(currentDashboard.round.number || 0);
        var from = byId("worker-submission-from").value;
        var to = byId("worker-submission-to").value;
        var requestId = ++workerSubmissionRequest;
        var query = new URLSearchParams();
        query.set("round", round);
        query.set("type", workerSubmissionMode);
        if (workerSubmissionRange !== "all") {
            query.set("from", from);
            query.set("to", to);
        }
        var emptyMessage = workerSubmissionMode === "visit" && round === 0
            ? "No visit submissions apply to Baseline (Round 0)."
            : "No field-worker submissions are available for this selection.";
        byId("worker-chart-caption").textContent = workerDateLabel();
        byId("field-worker-chart").innerHTML = '<div class="inline-note">Loading submissions…</div>';

        requestJson("api/field-worker-submissions?" + query.toString()).then(function (rows) {
            if (requestId !== workerSubmissionRequest || !currentDashboard
                    || round !== Number(currentDashboard.round.number || 0)) {
                return;
            }
            if (rows.length) {
                renderMiniBars("field-worker-chart", rows, "#238b83", "total");
            } else {
                byId("field-worker-chart").innerHTML = '<div class="inline-note">'
                    + escapeHtml(emptyMessage) + "</div>";
            }
        }).catch(function (error) {
            if (requestId !== workerSubmissionRequest) {
                return;
            }
            byId("field-worker-chart").innerHTML = '<div class="inline-note">'
                + escapeHtml("Could not load submissions: " + error.message) + "</div>";
        });
    }

    function updateExportLink() {
        byId("export-progress").href = "api/export.csv?round=" + currentRound() + "&by=" + progressMode;
    }

    function renderMiniBars(target, rows, color, valueKey) {
        var element = byId(target);
        if (!rows || !rows.length) {
            element.innerHTML = '<div class="inline-note">No round-specific records are available.</div>';
            return;
        }
        var max = Math.max.apply(null, rows.map(function (row) { return Number(row[valueKey] || 0); }).concat([1]));
        element.innerHTML = rows.map(function (row) {
            var value = Number(row[valueKey] || 0);
            return '<div class="mini-chart-row"><span class="mini-chart-label" title="' + escapeHtml(row.label) + '">' + escapeHtml(row.label) + '</span>'
                + '<span class="mini-chart-track"><span class="mini-chart-fill" style="display:block;width:' + (100 * value / max) + '%;background:' + color + '"></span></span>'
                + '<span class="mini-chart-value">' + number(value) + '</span></div>';
        }).join("");
    }

    function renderNewBaseline(data) {
        byId("new-houses").textContent = data.available ? number(data.houses) : "—";
        byId("new-population").textContent = data.available ? number(data.population) : "—";
        renderMiniBars("new-houses-chart", data.byCluster, "#4b98b1", "total");
        renderMiniBars("new-population-chart", data.populationByCluster, "#8273bf", "total");
        if (!data.available) {
            byId("new-houses-chart").innerHTML = '<div class="inline-note">' + escapeHtml(data.message || "Not available for baseline round.") + '</div>';
            byId("new-population-chart").innerHTML = '<div class="inline-note">' + escapeHtml(data.message || "Not available for baseline round.") + '</div>';
        }
    }

    function eventCard(event) {
        var available = event.available && event.count !== null;
        return '<article class="event-card' + (available ? "" : " unavailable") + '"><span>' + escapeHtml(event.label) + '</span>'
            + '<strong>' + (available ? number(event.count) : "Not available") + '</strong>'
            + '<small>' + (available ? escapeHtml(event.scope || "round-scoped") : escapeHtml(event.message || "Not scoped to this round")) + '</small></article>';
    }

    function renderEvents(events) {
        byId("vital-events-grid").innerHTML = events.map(eventCard).join("");
    }

    function renderUpdates(events) {
        byId("event-updates-grid").innerHTML = events.map(function (event) {
            var available = event.available && event.count !== null;
            return '<article class="update-card"><div><strong>' + escapeHtml(event.label) + '</strong><small>'
                + escapeHtml(available ? event.scope : event.message) + '</small></div><span class="update-count'
                + (available ? "" : " muted") + '">' + (available ? number(event.count) : "Not configured") + '</span></article>';
        }).join("");
    }

    function renderDashboard(data) {
        currentDashboard = data;
        renderBaseline(data.baseline);
        renderProgress(data.progress, data.round);
        loadWorkerSubmissions();
        renderNewBaseline(data.newBaseline);
        renderEvents(data.vitalEvents);
        renderUpdates(data.eventUpdates);
        renderDataSummary(data.roundSummary, data.round);
        byId("refresh-stamp").textContent = "Updated " + new Date(data.generatedAt).toLocaleString();
        byId("report-link").href = "report.html?round=" + data.round.number;
        byId("presentation-link").href = "presentation.html?round=" + data.round.number;
        byId("dashboard-content").hidden = false;
        byId("loading-state").hidden = true;
        byId("connection-error").hidden = true;
        updateActiveNavigation();
    }

    function renderMissedChart(rows) {
        var counts = {};
        rows.forEach(function (row) {
            var key = row.cluster || "Unassigned";
            counts[key] = (counts[key] || 0) + 1;
        });
        var chartRows = Object.keys(counts).map(function (key) {
            return { label: key, total: counts[key] };
        }).sort(function (a, b) { return b.total - a.total; }).slice(0, 8);
        renderMiniBars("missed-cluster-chart", chartRows, "#db7775", "total");
    }

    function renderMissedTable() {
        var query = byId("missed-search").value.toLowerCase().trim();
        var filtered = missedRows.filter(function (row) {
            return (row.kebele + " " + row.cluster + " " + row.houseId).toLowerCase().indexOf(query) >= 0;
        });
        var visible = filtered.slice(0, missedLimit);
        if (!visible.length) {
            byId("missed-table-body").innerHTML = '<tr><td colspan="3">No matching households.</td></tr>';
        } else {
            byId("missed-table-body").innerHTML = visible.map(function (row) {
                return "<tr><td>" + escapeHtml(row.kebele) + "</td><td>" + escapeHtml(row.cluster)
                    + "</td><td>" + escapeHtml(row.houseId) + "</td></tr>";
            }).join("");
        }
        byId("missed-shown").textContent = "Showing " + number(visible.length) + " of " + number(filtered.length);
        byId("show-all-missed").hidden = filtered.length <= missedLimit;
        byId("show-all-missed").textContent = "Show all " + number(filtered.length);
    }

    function loadMissed() {
        var round = currentRound();
        updateMissedExportLink();
        missedRows = [];
        missedRowsRound = null;
        byId("export-missed-pdf").disabled = true;
        byId("missed-total").textContent = "—";
        renderMissedChart([]);
        renderMissedTable();
        requestJson("api/not-visited?round=" + round).then(function (rows) {
            if (round !== currentRound()) {
                return;
            }
            missedRows = rows;
            missedRowsRound = round;
            byId("export-missed-pdf").disabled = false;
            missedLimit = 60;
            byId("missed-total").textContent = number(rows.length);
            renderMissedChart(rows);
            renderMissedTable();
        }).catch(function (error) {
            if (round === currentRound()) {
                showError(error.message);
            }
        });
    }

    function printNotVisitedHouseholds() {
        if (missedRowsRound !== currentRound()) {
            return;
        }
        var printWindow = window.open("", "_blank");
        if (!printWindow) {
            window.alert("Allow pop-ups for this site to print or save the household list as a PDF.");
            return;
        }
        var tableRows = missedRows.map(function (row) {
            return "<tr><td>" + escapeHtml(row.kebele) + "</td><td>" + escapeHtml(row.cluster)
                + "</td><td>" + escapeHtml(row.houseId) + "</td></tr>";
        }).join("");
        if (!tableRows) {
            tableRows = '<tr><td colspan="3">No households are outstanding for this round.</td></tr>';
        }
        printWindow.onload = function () {
            printWindow.focus();
            printWindow.print();
        };
        printWindow.document.open();
        printWindow.document.write(
            '<!doctype html><html lang="en"><head><meta charset="utf-8">'
            + '<meta name="viewport" content="width=device-width, initial-scale=1">'
            + '<title>Round ' + escapeHtml(currentRound()) + ' Not-Visited Households</title>'
            + '<style>body{font:12px Arial,sans-serif;color:#203744;margin:28px}'
            + 'h1{font-size:20px;margin:0 0 6px}p{color:#617581;margin:0 0 18px}'
            + 'table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:8px;'
            + 'border:1px solid #dce5e9}th{background:#f1f5f6;text-transform:uppercase;'
            + 'font-size:10px}@page{size:A4 portrait;margin:16mm}'
            + '@media print{thead{display:table-header-group}tr{break-inside:avoid}}</style>'
            + '</head><body><h1>Households Not Yet Visited</h1><p>Round '
            + escapeHtml(currentRound()) + ' · ' + number(missedRows.length)
            + ' households</p><table><thead><tr><th>Kebele</th><th>Cluster</th>'
            + '<th>Household ID</th></tr></thead><tbody>' + tableRows
            + '</tbody></table></body></html>');
        printWindow.document.close();
    }

    function popupText(row) {
        return "<strong>" + escapeHtml(row.houseId) + "</strong><br>"
            + "Kebele: " + escapeHtml(row.kebele) + "<br>"
            + "Cluster: " + escapeHtml(row.cluster) + "<br>"
            + "Status: " + (row.visited ? "Visited" : "Not visited");
    }

    function renderMap() {
        if (!window.L || !window.L.markerClusterGroup) {
            byId("field-map").innerHTML = '<div class="map-placeholder">Map tiles could not be loaded. Check the internet connection for Leaflet and OpenStreetMap access.</div>';
            return;
        }
        if (!map) {
            byId("field-map").innerHTML = "";
            map = L.map("field-map", { scrollWheelZoom: false }).setView([6.25, 38.22], 12);
            L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
                maxZoom: 19,
                attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
            }).addTo(map);
            mapGroups = {
                all: L.markerClusterGroup({ showCoverageOnHover: false }),
                visited: L.markerClusterGroup({ showCoverageOnHover: false }),
                "not-visited": L.markerClusterGroup({ showCoverageOnHover: false })
            };
            Object.keys(mapGroups).forEach(function (key) { map.addLayer(mapGroups[key]); });
        }
        Object.keys(mapGroups).forEach(function (key) { mapGroups[key].clearLayers(); });
        var validPoints = 0;
        var selectedRows = mapRows.filter(function (row) {
            return mapArea === "all" || mapAreaValue === "all" || String(row[mapArea]) === mapAreaValue;
        });
        selectedRows.forEach(function (row) {
            if (row.latitude === null || row.latitude === undefined || row.longitude === null || row.longitude === undefined) {
                return;
            }
            var lat = Number(row.latitude);
            var lng = Number(row.longitude);
            if (!isFinite(lat) || !isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
                return;
            }
            validPoints++;
            var color = row.visited ? "#168f70" : "#d65c62";
            var marker = L.circleMarker([lat, lng], {
                radius: 5, color: "#ffffff", weight: 1, fillColor: color, fillOpacity: .9
            }).bindPopup(popupText(row));
            mapGroups.all.addLayer(marker);
            mapGroups[row.visited ? "visited" : "not-visited"].addLayer(L.circleMarker([lat, lng], {
                radius: 5, color: "#ffffff", weight: 1, fillColor: color, fillOpacity: .9
            }).bindPopup(popupText(row)));
        });
        Object.keys(mapGroups).forEach(function (key) {
            if (key === mapFilter) {
                if (!map.hasLayer(mapGroups[key])) { map.addLayer(mapGroups[key]); }
            } else if (map.hasLayer(mapGroups[key])) {
                map.removeLayer(mapGroups[key]);
            }
        });
        if (validPoints && mapFilter === "all") {
            map.fitBounds(mapGroups.all.getBounds(), { padding: [22, 22], maxZoom: 15 });
        } else if (validPoints && mapGroups[mapFilter].getLayers().length) {
            map.fitBounds(mapGroups[mapFilter].getBounds(), { padding: [22, 22], maxZoom: 15 });
        }
        byId("map-count").textContent = number(validPoints) + " mapped households";
        if (!validPoints) {
            byId("field-map").innerHTML = '<div class="map-placeholder">No valid coordinates were found in the OpenHDS baseline household view.</div>';
            map.remove();
            map = null;
        }
    }

    function loadMap() {
        var round = currentRound();
        requestJson("api/locations?round=" + round).then(function (rows) {
            if (round !== currentRound()) {
                return;
            }
            mapRows = rows;
            populateMapAreas();
            renderMap();
        }).catch(function (error) {
            if (round === currentRound()) {
                showError(error.message);
            }
        });
    }

    function populateMapAreas() {
        var areaSelect = byId("map-area-filter");
        var values = [];
        if (mapArea !== "all") {
            values = mapRows.map(function (row) { return row[mapArea]; })
                .filter(function (value) { return value !== null && value !== undefined && String(value).length; })
                .filter(function (value, index, all) { return all.indexOf(value) === index; })
                .sort();
        }
        areaSelect.innerHTML = '<option value="all">All ' + (mapArea === "kebele" ? "Kebeles" : mapArea === "cluster" ? "clusters" : "areas") + '</option>';
        values.forEach(function (value) {
            var option = document.createElement("option");
            option.value = value;
            option.textContent = value;
            areaSelect.appendChild(option);
        });
        areaSelect.disabled = mapArea === "all";
        mapAreaValue = "all";
        areaSelect.value = "all";
    }

    function loadDashboard() {
        var round = currentRound();
        byId("loading-state").hidden = false;
        requestJson("api/dashboard?round=" + round).then(function (data) {
            if (round !== currentRound()) {
                return;
            }
            renderDashboard(data);
            loadMissed();
            loadMap();
        }).catch(function (error) {
            if (round === currentRound()) {
                showError(error.message);
            }
        });
    }

    byId("progress-track").addEventListener("click", function () {
        var detail = byId("progress-detail");
        detail.hidden = !detail.hidden;
        byId("expand-progress").setAttribute("aria-expanded", String(!detail.hidden));
    });
    byId("expand-progress").addEventListener("click", function () {
        byId("progress-track").click();
    });
    document.querySelectorAll("[data-progress-mode]").forEach(function (button) {
        button.addEventListener("click", function () {
            document.querySelectorAll("[data-progress-mode]").forEach(function (item) {
                item.classList.toggle("active", item === button);
            });
            progressMode = button.getAttribute("data-progress-mode");
            renderProgressBreakdown();
            updateExportLink();
        });
    });
    document.querySelectorAll("[data-worker-submission]").forEach(function (button) {
        button.addEventListener("click", function () {
            document.querySelectorAll("[data-worker-submission]").forEach(function (item) {
                var active = item === button;
                item.classList.toggle("active", active);
                item.setAttribute("aria-pressed", String(active));
            });
            workerSubmissionMode = button.getAttribute("data-worker-submission");
            loadWorkerSubmissions();
        });
    });
    document.querySelectorAll("[data-worker-range]").forEach(function (button) {
        button.addEventListener("click", function () {
            setWorkerDateRange(button.getAttribute("data-worker-range"), true);
        });
    });
    [byId("worker-submission-from"), byId("worker-submission-to")].forEach(function (input) {
        input.addEventListener("change", function () {
            workerSubmissionRange = "custom";
            document.querySelectorAll("[data-worker-range]").forEach(function (button) {
                button.classList.remove("active");
                button.setAttribute("aria-pressed", "false");
            });
            byId("worker-date-error").hidden = true;
        });
    });
    byId("apply-worker-dates").addEventListener("click", function () {
        var from = byId("worker-submission-from").value;
        var to = byId("worker-submission-to").value;
        if (!from || !to || from > to) {
            byId("worker-date-error").textContent = from && to
                ? "Start date must be on or before end date."
                : "Choose both a start date and an end date.";
            byId("worker-date-error").hidden = false;
            return;
        }
        workerSubmissionRange = "custom";
        loadWorkerSubmissions();
    });
    document.querySelectorAll("[data-map-filter]").forEach(function (button) {
        button.addEventListener("click", function () {
            document.querySelectorAll("[data-map-filter]").forEach(function (item) {
                item.classList.toggle("active", item === button);
            });
            mapFilter = button.getAttribute("data-map-filter");
            if (mapRows.length) { renderMap(); }
        });
    });
    byId("map-group-by").addEventListener("change", function () {
        mapArea = this.value;
        populateMapAreas();
        if (mapRows.length) { renderMap(); }
    });
    byId("map-area-filter").addEventListener("change", function () {
        mapAreaValue = this.value;
        if (mapRows.length) { renderMap(); }
    });
    byId("missed-search").addEventListener("input", renderMissedTable);
    byId("export-missed-pdf").addEventListener("click", printNotVisitedHouseholds);
    byId("show-all-missed").addEventListener("click", function () {
        missedLimit = missedRows.length;
        renderMissedTable();
    });
    roundSelect.addEventListener("change", loadDashboard);
    byId("sidebar-toggle").addEventListener("click", function () {
        var collapsed = document.body.classList.toggle("sidebar-collapsed");
        this.setAttribute("aria-expanded", String(!collapsed));
        this.setAttribute("aria-label", collapsed ? "Expand navigation" : "Collapse navigation");
        this.title = collapsed ? "Expand navigation" : "Collapse navigation";
        this.querySelector("span").textContent = collapsed ? "›" : "‹";
    });
    window.addEventListener("scroll", scheduleActiveNavigation);
    window.addEventListener("hashchange", updateActiveNavigation);
    window.addEventListener("resize", scheduleActiveNavigation);
    byId("open-settings").addEventListener("click", openSettings);
    byId("close-settings").addEventListener("click", closeSettings);
    byId("cancel-settings").addEventListener("click", closeSettings);
    byId("settings-form").addEventListener("submit", testSettings);
    byId("settings-overlay").addEventListener("click", function (event) {
        if (event.target === this) { closeSettings(); }
    });
    document.addEventListener("keydown", function (event) {
        if (event.key === "Escape" && !byId("settings-overlay").hidden) { closeSettings(); }
    });

    setWorkerDateRange("15days", false);
    updateActiveNavigation();
    requestJson("api/rounds").then(renderRounds).catch(showRoundsError);
}());
