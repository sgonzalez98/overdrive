package com.overdrive.app.trips;

import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * Mutable record representing a trip from start to finalization.
 * Contains all trip summary fields, Driving DNA scores, and references
 * to micro-moments JSON and telemetry file.
 */
public class TripRecord {

    public long id;                    // Auto-increment PK
    public long startTime;             // Epoch ms
    public long endTime;               // Epoch ms
    public double distanceKm;          // Odometer delta
    public int durationSeconds;
    public double avgSpeedKmh;
    public int maxSpeedKmh;
    public double socStart;            // %
    public double socEnd;              // %
    public double kwhStart;            // Remaining kWh at trip start (from BMS)
    public double kwhEnd;              // Remaining kWh at trip end (from BMS)
    public double efficiencySocPerKm;  // SoC% / km (legacy)
    public double energyPerKm;         // kWh / km (from BMS kWh readings)
    public double electricityRate;     // Cost per kWh at time of trip
    public String currency;            // Currency symbol (₹, $, €, £)
    public double tripCost;            // Total trip cost (electric leg + fuel leg)

    // ── PHEV / hybrid bookkeeping (BEV trips leave these at sentinel) ────
    // For BEVs: isPhev=false, all fuel* fields are 0/NaN, behavior is
    // identical to pre-PHEV builds.
    public boolean isPhev;             // Drivetrain at trip end
    public double fuelPctStart = -1;   // 0-100, -1 = unavailable
    public double fuelPctEnd = -1;     // 0-100, -1 = unavailable
    public double litresUsed;          // Computed litres burned this trip
    public double fuelPricePerL;       // Price snapshot at trip end
    public double fuelCost;            // litresUsed × fuelPricePerL
    public double electricCost;        // energyUsed × electricityRate
    // Cross-thread: incremented on the TripDetector scheduler thread (1Hz),
    // read on the gear/ACC thread when the trip finalises. AtomicInteger
    // gives us a published snapshot without reaching for a synchronized read.
    public final AtomicInteger iceSecondsAtomic = new AtomicInteger(0);
    /** Convenience accessor — most call sites only need a plain int. */
    public int iceSeconds() { return iceSecondsAtomic.get(); }
    public String kinematicState;      // HEAVY_GRIDLOCK, URBAN_FLOW, HIGHWAY_CRUISING
    public String gradientProfile;     // FLAT, HILLY, MOUNTAIN (terrain classification)
    public double elevationGainM;      // Cumulative meters gained (uphill)
    public double elevationLossM;      // Cumulative meters lost (downhill)
    public double avgGradientPercent;  // Average gradient over the trip
    public double startLat, startLon;
    public double endLat, endLon;
    public int extTempC;

    // Driving DNA scores (0-100)
    public int anticipationScore;
    public int smoothnessScore;
    public int speedDisciplineScore;
    public int efficiencyScore;
    public int consistencyScore;

    public String microMomentsJson;    // JSON blob
    public String telemetryFilePath;   // Path to .jsonl.gz
    public long routeId = -1;          // Route cluster ID for O(1) similar-trip lookups

    // ── Storage accounting (server-internal) ─────────────────────────────
    // Byte size of the .jsonl.gz telemetry file at finalize time. Stored
    // so StorageManager.getTripsSize() can answer via SUM(size_bytes)
    // instead of walking every trips dir + stat()ing every file via FUSE
    // (which took 10-20 minutes on full storage). 0 = legacy row not yet
    // backfilled; the size-backfill thread fills these on first run.
    // sidecarSizeBytes is reserved for future trip sidecar files (gps trace
    // etc.); current builds have no sidecars and leave it at 0.
    public long sizeBytes;
    public long sidecarSizeBytes;

    /**
     * Compute the overall Driving DNA score as the average of all 5 axis scores.
     */
    public int getOverallScore() {
        return (int) Math.round((anticipationScore + smoothnessScore + speedDisciplineScore
                + efficiencyScore + consistencyScore) / 5.0);
    }

    /**
     * Get the actual energy consumed in kWh from BMS readings.
     * Returns 0 if kWh data not available (caller should use SoC-based estimation).
     * Always non-negative — used for cost and total-energy accounting.
     */
    public double getEnergyUsedKwh() {
        if (kwhStart > 0 && kwhEnd > 0 && kwhStart > kwhEnd) {
            return kwhStart - kwhEnd;
        }
        return 0;
    }

    /**
     * Get the trip's resolved energy use in kWh for ENERGY ACCOUNTING (rollup
     * totals). Prefers the direct BMS measurement; when that's absent, falls back
     * to {@code energyPerKm × distanceKm} — the same SoC-estimated figure that
     * {@link #energyPerKm} and the trip cost were computed from upstream. This
     * keeps a rollup's total_energy_kwh consistent with its avg_energy_per_km and
     * total_cost on SoC-only trims (where {@link #getEnergyUsedKwh()} returns 0).
     * Always non-negative.
     */
    public double getResolvedEnergyKwh() {
        double bms = getEnergyUsedKwh();
        if (bms > 0) return bms;
        if (energyPerKm > 0 && distanceKm > 0) return energyPerKm * distanceKm;
        return 0;
    }

    /**
     * Get the SIGNED net energy in kWh from BMS readings: positive when the pack
     * drained (normal driving), negative when it gained (regen-dominant descent).
     * Returns 0 only when BMS kWh readings aren't available at all.
     *
     * <p>Used by the efficiency score so a long downhill that nets battery gain
     * scores as excellent rather than neutral. Only the BMS-kWh path is signed;
     * the SoC-delta fallback stays consumption-only because 1%-resolution SoC is
     * too noisy to distinguish genuine regen from sensor jitter.
     */
    public double getSignedEnergyKwh() {
        if (kwhStart > 0 && kwhEnd > 0) {
            return kwhStart - kwhEnd; // negative when kwhEnd > kwhStart (net regen)
        }
        return 0;
    }

    /**
     * Serialize all fields to JSON (full detail, including micro-moments).
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("startTime", startTime);
            json.put("endTime", endTime);
            json.put("distanceKm", distanceKm);
            json.put("durationSeconds", durationSeconds);
            json.put("avgSpeedKmh", avgSpeedKmh);
            json.put("maxSpeedKmh", maxSpeedKmh);
            json.put("socStart", socStart);
            json.put("socEnd", socEnd);
            json.put("kwhStart", kwhStart);
            json.put("kwhEnd", kwhEnd);
            json.put("energyUsedKwh", getEnergyUsedKwh());
            json.put("efficiencySocPerKm", efficiencySocPerKm);
            json.put("energyPerKm", energyPerKm);
            json.put("electricityRate", electricityRate);
            json.put("currency", currency != null ? currency : "");
            json.put("tripCost", tripCost);
            json.put("kinematicState", kinematicState != null ? kinematicState : "");
            json.put("gradientProfile", gradientProfile != null ? gradientProfile : "");
            json.put("elevationGainM", elevationGainM);
            json.put("elevationLossM", elevationLossM);
            json.put("avgGradientPercent", avgGradientPercent);
            json.put("startLat", startLat);
            json.put("startLon", startLon);
            json.put("endLat", endLat);
            json.put("endLon", endLon);
            json.put("extTempC", extTempC);
            json.put("anticipationScore", anticipationScore);
            json.put("smoothnessScore", smoothnessScore);
            json.put("speedDisciplineScore", speedDisciplineScore);
            json.put("efficiencyScore", efficiencyScore);
            json.put("consistencyScore", consistencyScore);
            json.put("overallScore", getOverallScore());
            json.put("isPhev", isPhev);
            if (fuelPctStart >= 0) json.put("fuelPctStart", fuelPctStart);
            if (fuelPctEnd >= 0) json.put("fuelPctEnd", fuelPctEnd);
            json.put("litresUsed", litresUsed);
            json.put("fuelPricePerL", fuelPricePerL);
            json.put("fuelCost", fuelCost);
            json.put("electricCost", electricCost);
            json.put("iceSeconds", iceSeconds());
            json.put("microMomentsJson", microMomentsJson != null ? microMomentsJson : "");
            json.put("telemetryFilePath", telemetryFilePath != null ? telemetryFilePath : "");
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }

    /**
     * Serialize to summary JSON (excludes microMomentsJson for list views).
     */
    public JSONObject toSummaryJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("startTime", startTime);
            json.put("endTime", endTime);
            json.put("distanceKm", distanceKm);
            json.put("durationSeconds", durationSeconds);
            json.put("avgSpeedKmh", avgSpeedKmh);
            json.put("maxSpeedKmh", maxSpeedKmh);
            json.put("socStart", socStart);
            json.put("socEnd", socEnd);
            json.put("kwhStart", kwhStart);
            json.put("kwhEnd", kwhEnd);
            json.put("energyUsedKwh", getEnergyUsedKwh());
            json.put("efficiencySocPerKm", efficiencySocPerKm);
            json.put("energyPerKm", energyPerKm);
            json.put("electricityRate", electricityRate);
            json.put("currency", currency != null ? currency : "");
            json.put("tripCost", tripCost);
            json.put("kinematicState", kinematicState != null ? kinematicState : "");
            json.put("gradientProfile", gradientProfile != null ? gradientProfile : "");
            json.put("elevationGainM", elevationGainM);
            json.put("elevationLossM", elevationLossM);
            json.put("avgGradientPercent", avgGradientPercent);
            json.put("startLat", startLat);
            json.put("startLon", startLon);
            json.put("endLat", endLat);
            json.put("endLon", endLon);
            json.put("extTempC", extTempC);
            json.put("anticipationScore", anticipationScore);
            json.put("smoothnessScore", smoothnessScore);
            json.put("speedDisciplineScore", speedDisciplineScore);
            json.put("efficiencyScore", efficiencyScore);
            json.put("consistencyScore", consistencyScore);
            json.put("overallScore", getOverallScore());
            json.put("isPhev", isPhev);
            if (fuelPctStart >= 0) json.put("fuelPctStart", fuelPctStart);
            if (fuelPctEnd >= 0) json.put("fuelPctEnd", fuelPctEnd);
            json.put("litresUsed", litresUsed);
            json.put("fuelPricePerL", fuelPricePerL);
            json.put("fuelCost", fuelCost);
            json.put("electricCost", electricCost);
            json.put("iceSeconds", iceSeconds());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }
}
