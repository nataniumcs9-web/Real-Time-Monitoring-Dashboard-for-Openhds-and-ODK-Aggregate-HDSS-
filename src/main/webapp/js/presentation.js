(function () {
    "use strict";

    var round = Math.max(0, Number(new URLSearchParams(window.location.search).get("round") || 0));
    var current = 0;
    var slides = [];
    var deckMap = null;
    var locationRows = [];
    function el(id) { return document.getElementById(id); }
    function fmt(value) { return value === null || value === undefined || isNaN(Number(value)) ? "—" : Number(value).toLocaleString("en-US"); }
    function esc(value) {
        return String(value === null || value === undefined ? "" : value).replace(/&/g, "&amp;")
            .replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
    }
    function getJson(url) {
        return fetch(url, { headers: { "Accept": "application/json" } }).then(function (response) {
            return response.json().then(function (data) {
                if (!response.ok) { throw new Error(data.error || "Request failed."); }
                return data;
            });
        });
    }
    function chart(rows, valueKey, max) {
        return rows.map(function (row) {
            var value = Number(row[valueKey] || 0);
            var width = Math.min(100, (value / Math.max(1, max)) * 100);
            return '<div class="slide-chart-row"><span title="' + esc(row.label) + '">' + esc(row.label) + '</span>'
                + '<span class="slide-chart-track"><span class="slide-chart-fill" style="display:block;width:' + width + '%"></span></span>'
                + '<strong>' + fmt(value) + '</strong></div>';
        }).join("");
    }
    function kpis(data) {
        return '<div class="slide-kpis">' + [
            ["Baseline households", data.baseline.households],
            ["Population", data.baseline.population],
            ["Social groups", data.baseline.socialGroups],
            ["Relationships", data.baseline.relationships]
        ].map(function (item) {
            return '<div class="slide-kpi"><span>' + esc(item[0]) + '</span><strong>' + fmt(item[1]) + '</strong></div>';
        }).join("") + "</div>";
    }
    function eventCards(events) {
        return events.map(function (event) {
            return '<div class="slide-event"><span>' + esc(event.label) + '</span><strong>'
                + (event.available ? fmt(event.count) : "—") + '</strong></div>';
        }).join("");
    }
    function makeSlides(data) {
        var p = data.progress;
        var pct = p.completionPct === null ? 0 : Number(p.completionPct || 0);
        var dateText = data.round.startDate ? data.round.startDate + " – " + (data.round.endDate || "open") : "Dates not set";
        var clusters = p.byCluster.slice().sort(function (a, b) { return Number(b.total) - Number(a.total); }).slice(0, 12);
        var progressMax = Math.max.apply(null, clusters.map(function (item) { return Number(item.total || 0); }).concat([1]));
        var newHouses = data.newBaseline.byCluster || [];
        var eventSummary = data.vitalEvents.filter(function (event) {
            return event.available && event.count !== null;
        });
        var markup = [
            '<section class="slide slide-cover active"><div class="eyebrow">WONAGO HEALTH &amp; DEMOGRAPHIC SURVEILLANCE SYSTEM</div><h1>'
                + esc(data.round.label) + ' Monitoring Briefing</h1><p>Household fieldwork coverage, newly registered households and population, and round-specific vital events.</p><p>'
                + esc(dateText) + '</p><div class="slide-footer"><span>Wonago HDSS Round Monitor</span><span>Operational briefing</span></div></section>',
            '<section class="slide"><div class="eyebrow">REFERENCE POPULATION</div><h2>Baseline at a glance</h2><p class="slide-subtitle">Round 0 household and population denominators</p>'
                + kpis(data) + '<div class="slide-footer"><span>OpenHDS reference views and active records</span><span>Round ' + data.round.number + '</span></div></section>',
            '<section class="slide"><div class="eyebrow">ROUND ' + data.round.number + ' · FIELDWORK</div><h2>Household visit progress</h2><div class="slide-stat-row"><strong>'
                + (p.tracking ? pct.toFixed(1) + "%" : "Baseline") + '</strong><span>' + (p.tracking ? fmt(p.visited) + ' of ' + fmt(p.totalHouseholds) + ' households visited' : 'No active-round progress at baseline') + '</span></div>'
                + '<div class="slide-progress"><span style="width:' + pct + '%"></span></div><div class="slide-stat-row"><span><strong>'
                + (p.tracking ? fmt(p.notVisited) : "—") + '</strong> not visited</span></div><div class="slide-footer"><span>houseid matched to OPENHDS_LOCATION_ID</span><span>' + esc(dateText) + '</span></div></section>',
            '<section class="slide"><div class="eyebrow">GEOGRAPHIC COVERAGE</div><h2>Household progress by cluster</h2><p class="slide-subtitle">Showing the largest baseline clusters</p><div class="slide-chart">'
                + chart(clusters, "visited", progressMax) + '</div><div class="slide-footer"><span>Bar lengths use the baseline household total as scale</span><span>Visited count</span></div></section>',
            '<section class="slide"><div class="eyebrow">ROUND ' + data.round.number + ' · BASELINE CHANGE</div><h2>New households and population</h2><div class="slide-event-grid"><div class="slide-event"><span>New households</span><strong>'
                + (data.newBaseline.available ? fmt(data.newBaseline.houses) : "—") + '</strong></div><div class="slide-event"><span>New individuals</span><strong>'
                + (data.newBaseline.available ? fmt(data.newBaseline.population) : "—") + '</strong></div></div><p class="slide-subtitle">New households by cluster</p><div class="slide-chart">'
                + chart(newHouses.slice(0, 8), "total", Math.max.apply(null, newHouses.map(function (x) { return Number(x.total || 0); }).concat([1])))
                + '</div><div class="slide-footer"><span>Round-linked ODK baseline forms</span><span>Review unmapped clusters</span></div></section>',
            '<section class="slide"><div class="eyebrow">DEMOGRAPHIC CHANGE</div><h2>Vital events recorded</h2><p class="slide-subtitle">Event submissions for the selected round</p><div class="slide-event-grid">'
                + eventCards(data.vitalEvents) + '</div><div class="slide-footer"><span>Unavailable counts are not treated as zero</span><span>Round ' + data.round.number + '</span></div></section>',
            '<section class="slide"><div class="eyebrow">SPATIAL COVERAGE</div><h2>Visited and not-visited households</h2><p class="slide-subtitle"><span style="color:#168f70">● Visited</span> &nbsp; <span style="color:#d65c62">● Not visited</span></p><div id="deck-map" class="slide-map"></div><div class="slide-footer"><span>OpenStreetMap tiles · household markers clustered</span><span>Use dashboard for individual follow-up IDs</span></div></section>',
            '<section class="slide"><div class="eyebrow">FOLLOW-UP PRIORITIES</div><h2>Next operational steps</h2><p class="slide-subtitle">Priorities generated from the selected round’s coverage and data availability.</p><div class="slide-list"><div>Review the clusters with the highest not-visited household count and assign follow-up visits.</div><div>Check ODK-to-OpenHDS household ID matches and resolve unassigned or unmapped records.</div><div>Confirm that round dates are set for forms that require date-window linkage.</div><div>Verify vital-event and optional update-form totals against source forms before publication.</div></div><div class="slide-footer"><span>Generated from the connected OpenHDS and ODK databases</span><span>End of briefing</span></div></section>'
        ].join("");
        el("slides").innerHTML = markup;
        slides = Array.prototype.slice.call(document.querySelectorAll(".slide"));
        el("deck-round-label").textContent = data.round.label + " · " + dateText;
        showSlide(0);
        renderMap();
    }
    function showSlide(index) {
        current = Math.max(0, Math.min(slides.length - 1, index));
        slides.forEach(function (slide, i) { slide.classList.toggle("active", i === current); });
        el("slide-count").textContent = (current + 1) + " / " + slides.length;
        el("prev-slide").disabled = current === 0;
        el("next-slide").disabled = current === slides.length - 1;
        if (current === 6 && deckMap) { window.setTimeout(function () { deckMap.invalidateSize(); }, 100); }
    }
    function renderMap() {
        if (!window.L || !window.L.markerClusterGroup) { return; }
        var container = el("deck-map");
        deckMap = L.map(container, { scrollWheelZoom: false }).setView([6.25, 38.22], 12);
        L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
            maxZoom: 19, attribution: '&copy; OpenStreetMap contributors'
        }).addTo(deckMap);
        var group = L.markerClusterGroup({ showCoverageOnHover: false });
        var count = 0;
        locationRows.forEach(function (row) {
            if (row.latitude === null || row.latitude === undefined || row.longitude === null || row.longitude === undefined) { return; }
            var lat = Number(row.latitude), lng = Number(row.longitude);
            if (!isFinite(lat) || !isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) { return; }
            count++;
            L.circleMarker([lat, lng], { radius: 4, color: "#fff", weight: 1,
                fillColor: row.visited ? "#168f70" : "#d65c62", fillOpacity: .9 })
                .bindPopup(esc(row.kebele) + " · " + esc(row.cluster) + "<br>" + (row.visited ? "Visited" : "Not visited"))
                .addTo(group);
        });
        deckMap.addLayer(group);
        if (count) { deckMap.fitBounds(group.getBounds(), { padding: [12, 12], maxZoom: 14 }); }
    }
    el("prev-slide").addEventListener("click", function () { showSlide(current - 1); });
    el("next-slide").addEventListener("click", function () { showSlide(current + 1); });
    el("deck-print").addEventListener("click", function () { window.print(); });
    document.addEventListener("keydown", function (event) {
        if (event.key === "ArrowRight" || event.key === " ") { showSlide(current + 1); }
        if (event.key === "ArrowLeft") { showSlide(current - 1); }
    });
    Promise.all([
        getJson("api/dashboard?round=" + round),
        getJson("api/locations?round=" + round)
    ]).then(function (result) {
        locationRows = result[1];
        makeSlides(result[0]);
    }).catch(function (error) {
        el("deck-status").textContent = "Could not prepare briefing: " + error.message;
    });
}());
