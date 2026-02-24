package com.spawnscaling;

import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.responsecurve.ScaledXYResponseCurve;
import com.hypixel.hytale.server.core.asset.type.responsecurve.config.ResponseCurve;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.spawning.assets.spawns.config.BeaconNPCSpawn;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

/**
 * SpawnScaling - Maintains vanilla per-player spawn experience regardless of player count.
 *
 * Injects MaxSpawnsScalingCurve into all beacon spawns at runtime based on their
 * current MaxSpawnedNPCs value. This ensures each player sees the same number of
 * enemies as they would in single-player, scaling linearly with player count.
 */
public class SpawnScalingPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int MAX_PLAYERS_TO_SCALE = 20;

    public SpawnScalingPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("[SpawnScaling] Setting up spawn scaling plugin");
    }

    @Override
    protected void start() {
        LOGGER.atInfo().log("[SpawnScaling] Injecting scaling curves into beacon spawns");
        injectScalingCurves();
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("[SpawnScaling] Plugin shutdown");
    }

    private void injectScalingCurves() {
        try {
            IndexedLookupTableAssetMap<String, BeaconNPCSpawn> beaconMap = BeaconNPCSpawn.getAssetMap();
            Map<String, BeaconNPCSpawn> beacons = beaconMap.getAssetMap();

            int successCount = 0;
            int skipCount = 0;

            for (Map.Entry<String, BeaconNPCSpawn> entry : new ArrayList<>(beacons.entrySet())) {
                String beaconId = entry.getKey();
                BeaconNPCSpawn beacon = entry.getValue();

                // Check if already has a scaling curve
                if (beacon.getMaxSpawnsScalingCurve() != null) {
                    skipCount++;
                    continue;
                }

                int baseMaxSpawns = beacon.getMaxSpawnedNpcs();

                // Create scaling curve: for N players, total = base * N
                // Since curve ADDS to base, we need: additionalSpawns = base * (N - 1)
                // At 1 player: base + 0 = base
                // At 2 players: base + base = 2*base
                // At MAX_PLAYERS: base + base*(MAX-1) = base*MAX
                double maxAdditional = baseMaxSpawns * (MAX_PLAYERS_TO_SCALE - 1);

                ScaledXYResponseCurve curve = createLinearScalingCurve(
                    1.0, MAX_PLAYERS_TO_SCALE,  // XRange: player count
                    0.0, maxAdditional           // YRange: additional spawns
                );

                if (curve != null && injectCurve(beacon, curve)) {
                    successCount++;
                    LOGGER.atFine().log("[SpawnScaling] Injected curve for %s (base=%d, maxAdditional=%.0f)",
                        beaconId, baseMaxSpawns, maxAdditional);
                }
            }

            LOGGER.atInfo().log("[SpawnScaling] Injection complete: %d beacons modified, %d skipped (already had curves)",
                successCount, skipCount);

        } catch (Exception e) {
            LOGGER.atSevere().log("[SpawnScaling] Failed to inject scaling curves: %s", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Creates a linear scaling curve with the given ranges.
     * The curve interpolates linearly from yMin at xMin to yMax at xMax.
     */
    private ScaledXYResponseCurve createLinearScalingCurve(
            double xMin, double xMax,
            double yMin, double yMax) {
        try {
            // Create the curve using the public constructor
            ScaledXYResponseCurve curve = new ScaledXYResponseCurve(
                "Linear",
                new double[]{xMin, xMax},
                new double[]{yMin, yMax}
            );

            // Set up the responseCurveReference field (required for the curve to work)
            // This mimics what the CODEC's afterDecode does
            IndexedLookupTableAssetMap<String, ResponseCurve> curveMap = ResponseCurve.getAssetMap();
            int index = curveMap.getIndex("Linear");
            ResponseCurve linearCurve = curveMap.getAsset(index);

            if (linearCurve == null) {
                LOGGER.atWarning().log("[SpawnScaling] 'Linear' response curve not found in assets");
                return null;
            }

            // Use reflection to set the responseCurveReference field
            Field refField = ScaledXYResponseCurve.class.getSuperclass()
                .getDeclaredField("responseCurveReference");
            refField.setAccessible(true);

            // Create the Reference object
            ResponseCurve.Reference ref = new ResponseCurve.Reference(index, linearCurve);
            refField.set(curve, ref);

            return curve;

        } catch (Exception e) {
            LOGGER.atSevere().log("[SpawnScaling] Failed to create scaling curve: %s", e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Injects a scaling curve into a beacon spawn using reflection.
     */
    private boolean injectCurve(BeaconNPCSpawn beacon, ScaledXYResponseCurve curve) {
        try {
            Field field = BeaconNPCSpawn.class.getDeclaredField("maxSpawnsScalingCurve");
            field.setAccessible(true);
            field.set(beacon, curve);
            return true;
        } catch (Exception e) {
            LOGGER.atWarning().log("[SpawnScaling] Failed to inject curve into beacon: %s", e.getMessage());
            return false;
        }
    }
}
