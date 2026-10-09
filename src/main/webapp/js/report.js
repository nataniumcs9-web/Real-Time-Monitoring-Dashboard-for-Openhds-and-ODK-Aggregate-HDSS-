(function () {
    "use strict";

    var params = new URLSearchParams(window.location.search);
    var round = Math.max(0, Number(params.get("round") || 0));
    var reportMap = null;
    var reportLocations = [];

    function byId(id) { return document.getElementById(id); }
    function fmt(value) {
        return value === null || value === undefined || isNaN(Number(value))
            ? "—" : Number(value).toLocaleString("en-US");
    }
    function esc(value) {
        return String(value === null || value === undefined ? "" : value)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
    }
    function getJson(url) {
        return fetch(url, { headers: { "Accept": "application/json" } }).then(function (response) {
            return response.json().then(function (data) {
                if (!response.ok) { throw new Error(data.error || "Unable to load report data."); }
                return data;
            });
        });
    }
    function table(headers, rows) {
        return "<table><thead><tr>" + headers.map(function (cell) { return "<th>" + esc(cell) + "</th>"; }).join("")
            + "</tr></thead><tbody>" + rows.map(function (row) {
                return "<tr>" + row.map(function (cell) { return "<td>" + esc(cell) + "</td>"; }).join("") + "</tr>";
            }).join("") + "</tbody></table>";
    }
    function metricCards(items) {
        return items.map(function (item) {
            return '<div class="report-kpi"><span>' + esc(item[0]) + '</span><strong>' + esc(fmt(item[1])) + '</strong></div>';
        }).join("");
    }
    function countRows(rows, key) {
        return (rows || []).map(function (row) { return [row.label, fmt(row[key])]; });
    }
    function renderReport(data, missed) {
        var roundInfo = data.round;
        var baseline = data.baseline;
        var progress = data.progress;
        var pct = progress.completionPct === null ? 0 : Number(progress.completionPct || 0);
        var period = roundInfo.startDate
            ? roundInfo.startDate + " to " + (roundInfo.endDate || "date not set")
            : "Round dates not provided";
        byId("report-title").textContent = roundInfo.label + " Monitoring Report";
        byId("report-subtitle").textContent = "Fieldwork progress, baseline change and vital event summary";
        byId("report-period").textContent = period;
        byId("report-created").textContent = "Generated " + new Date(data.generatedAt).toLocaleString();
        byId("footer-generated").textContent = new Date(data.generatedAt).toLocaleString();
        byId("report-intro").textContent = roundInfo.number === 0
            ? "This baseline section establishes the current OpenHDS reference population before round-based follow-up. Active-round visit coverage and round-specific events are not applicable to round 0."
            : "This report summarizes monitoring activity recorded for " + roundInfo.label + ". Baseline totals provide the household and population denominator; ODK visit submissions are matched to baseline households using houseid and OPENHDS_LOCATION_ID.";
        byId("report-kpis").innerHTML = metricCards([
            ["Baseline households", baseline.households],
            ["Baseline population", baseline.population],
            ["Social groups", baseline.socialGroups],
            ["Relationships", baseline.relationships]
        ]);
        var kebeles = {};
        (baseline.householdsByKebele || []).forEach(function (row) {
            kebeles[row.label] = { kebele: row.label, households: row.total, population: 0 };
        });
        (baseline.populationByKebele || []).forEach(function (row) {
            if (!kebeles[row.label]) {
                kebeles[row.label] = { kebele: row.label, households: 0, population: row.total };
            } else {
                kebeles[row.label].population = row.total;
            }
        });
        var kebeleRows = Object.keys(kebeles).sort().map(function (label) {
            return [label, fmt(kebeles[label].households), fmt(kebeles[label].population)];
        });
        kebeleRows.push(["Total", fmt(baseline.households), fmt(baseline.population)]);
        byId("kebele-table").innerHTML = table(["Kebele", "Households", "Population"], kebeleRows);
        byId("sex-table").innerHTML = table(["Sex", "Population"], countRows(baseline.populationBySex, "total"));
        byId("profile-note").textContent = "Counts use distinct house and individual identifiers in the supplied baseline views. These summaries describe the current baseline register; they are not round-to-round demographic change estimates.";
        if (progress.tracking) {
            byId("coverage-narrative").textContent = fmt(progress.visited) + " rows from odk_prod.visited compared with "
                + fmt(progress.totalHouseholds) + " baseline households for " + roundInfo.label
                + " (" + pct.toFixed(1) + "%). The baseline-minus-visited difference is "
                + fmt(progress.notVisited) + ".";
        } else {
            byId("coverage-narrative").textContent = "Round 0 is the baseline denominator. Select an active round to report visit completion and follow-up households.";
        }
        byId("report-progress-fill").style.width = Math.min(100, Math.max(0, pct)) + "%";
        byId("cluster-table").innerHTML = table(
            ["Cluster", "Baseline households", "Visited", "Not visited", "Completion"],
            (progress.byCluster || []).map(function (row) {
                return [row.label, fmt(row.total), fmt(row.visited), fmt(row.notVisited),
                    Number(row.completionPct || 0).toFixed(1) + "%"];
            }));
        byId("new-baseline-summary").innerHTML = data.newBaseline.available
            ? "<p>ODK table row counts: " + fmt(data.newBaseline.houses) + " new household records and "
                + fmt(data.newBaseline.population) + " new population records.</p>"
            : "<p>" + esc(data.newBaseline.message || "Round-specific registrations are not available for round 0.") + "</p>";
        byId("new-baseline-tables").innerHTML =
            '<div><h3>New households by cluster</h3>' + table(["Cluster", "New households"], countRows(data.newBaseline.byCluster, "total")) + '</div>'
            + '<div><h3>New population by cluster</h3>' + table(["Cluster", "New individuals"], countRows(data.newBaseline.populationByCluster, "total")) + '</div>';
        byId("vital-table").innerHTML = table(["Event", "Count", "Round linkage", "Status"],
            data.vitalEvents.map(function (event) {
                return [event.label, event.available ? fmt(event.count) : "Not available",
                    event.scope || "—", event.available ? "Available" : event.message];
            }));
        byId("updates-table").innerHTML = table(["Update form", "Count", "Count basis", "Status"],
            data.eventUpdates.map(function (event) {
                return [event.label, event.available ? fmt(event.count) : "Not available",
                    event.scope || "—", event.available ? "Available" : event.message];
            }));
        var notes = [
            "Baseline household and population totals come from OpenHDS allpopkebele2 and allpopkebele views. The report counts distinct household IDs and individual UUIDs.",
            "The household-visit progress total counts rows in odk_prod.visited. Cluster and field-worker breakdowns continue to use the selected round's visit-registration records.",
            "New household and population totals count rows in odk_prod.new_baseline and odk_prod.new_population. Their cluster summaries use the first three characters of OPENHDS_LOCATION_ID and INDIVIDUAL_INFO_INDIVIDUAL_ID, respectively.",
            "Event-update counts are direct row counts from their respective ODK form tables and are not filtered by the selected round.",
            "Vital-event counts remain linked to the selected round through visit IDs or round date windows, where available. Unavailable forms are not represented as zero.",
            "The household map uses coordinates from the OpenHDS allpopkebele2 baseline view and matches visit status by houseid and OPENHDS_LOCATION_ID for the selected round.",
            "Not-visited household details are operational follow-up information. Protect this report and map as sensitive location data."
        ];
        byId("method-notes").innerHTML = notes.map(function (note) { return "<li>" + esc(note) + "</li>"; }).join("");
        byId("map-summary").innerHTML = table(["Kebele", "Cluster", "Not-visited households"],
            topMissedClusters(missed));
        byId("slide-link").href = "presentation.html?round=" + roundInfo.number;
        byId("report-content").hidden = false;
        byId("report-status").hidden = true;
        renderMap(reportLocations);
    }
    function topMissedClusters(rows) {
        var counts = {};
        rows.forEach(function (row) {
            var key = row.kebele + "\t" + row.cluster;
            counts[key] = (counts[key] || 0) + 1;
        });
        return Object.keys(counts).map(function (key) {
            return key.split("\t").concat([fmt(counts[key])]);
        }).sort(function (a, b) { return Number(b[2].replace(/,/g, "")) - Number(a[2].replace(/,/g, "")); });
    }
    function renderMap(rows) {
        if (!window.L) {
            byId("report-map").innerHTML = '<div class="map-placeholder">Map tiles unavailable. See the dashboard map for interactive coverage.</div>';
            return;
        }
        byId("report-map").innerHTML = "";
        reportMap = L.map("report-map", { scrollWheelZoom: false }).setView([6.25, 38.22], 12);
        L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(reportMap);
        var group = L.markerClusterGroup({ showCoverageOnHover: false });
        var count = 0;
        rows.forEach(function (row) {
            if (row.latitude === null || row.latitude === undefined || row.longitude === null || row.longitude === undefined) { return; }
            var lat = Number(row.latitude);
            var lng = Number(row.longitude);
            if (!isFinite(lat) || !isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) { return; }
            count++;
            var color = row.visited ? "#168f70" : "#d65c62";
            L.circleMarker([lat, lng], { radius: 4, color: "#fff", weight: 1, fillColor: color, fillOpacity: .9 })
                .bindPopup("<strong>" + esc(row.houseId) + "</strong><br>" + esc(row.kebele) + " · " + esc(row.cluster)
                    + "<br>" + (row.visited ? "Visited" : "Not visited")).addTo(group);
        });
        reportMap.addLayer(group);
        if (count) { reportMap.fitBounds(group.getBounds(), { padding: [18, 18], maxZoom: 14 }); }
        else { byId("report-map").innerHTML = '<div class="map-placeholder">No household coordinates were returned.</div>'; }
    }
    function exportWord() {
        var css = "";
        var urls = ["css/dashboard.css", "css/report.css"];
        Promise.all(urls.map(function (url) {
            return fetch(url).then(function (response) { return response.text(); });
        })).then(function (parts) {
            css = parts.join("\n");
            var content = byId("report-content").cloneNode(true);
            var mapElement = content.querySelector("#report-map");
            if (mapElement && mapElement.parentNode) {
                mapElement.parentNode.removeChild(mapElement);
            }
            var html = '<!doctype html><html><head><meta charset="utf-8"><style>' + css
                + '</style></head><body class="report-page">' + content.outerHTML + '</body></html>';
            var blob = new Blob(["\ufeff", html], { type: "application/msword;charset=utf-8" });
            var link = document.createElement("a");
            link.href = URL.createObjectURL(blob);
            link.download = "wonago-round-" + round + "-report.doc";
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            window.setTimeout(function () { URL.revokeObjectURL(link.href); }, 1000);
        }).catch(function (error) {
            byId("report-status").textContent = "Word export failed: " + error.message;
            byId("report-status").hidden = false;
        });
    }

    byId("word-export").addEventListener("click", exportWord);
    byId("pdf-export").addEventListener("click", function () { window.print(); });
    Promise.all([
        getJson("api/dashboard?round=" + round),
        getJson("api/not-visited?round=" + round),
        getJson("api/locations?round=" + round)
    ]).then(function (results) {
        reportLocations = results[2];
        renderReport(results[0], results[1]);
    }).catch(function (error) {
        byId("report-status").className = "report-status-error";
        byId("report-status").textContent = "Could not build report: " + error.message;
    });
}());
