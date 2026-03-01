package com.spawnscaling;

import com.hccore.api.HC_CoreAPI;
import com.hccore.models.SettingDef;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SpawnScaling - Maintains vanilla per-player spawn experience regardless of player count.
 *
 * Injects MaxSpawnsScalingCurve into all beacon spawns at runtime based on their
 * current MaxSpawnedNPCs value. This ensures each player sees the same number of
 * enemies as they would in single-player, scaling linearly with player count.
 *
 * Also supports a flat mob density multiplier (mobDensityMultiplier) that increases
 * the number of mobs alive at once for all player counts. At 2.0, twice as many mobs
 * can be alive per beacon. Requires server restart to take effect.
 *
 * Hostile beacons (those with NPCSpawnState set, e.g. "Chase") receive full density
 * and per-player scaling. Passive/ambient beacons (no NPCSpawnState -- fish, animals)
 * receive a separate, reduced multiplier (passiveDensityMultiplier) to prevent them
 * from consuming mob budget that hostile spawns need.
 *
 * Combat Despawn Protection (HYC-167):
 * Hostile beacons have their NPCIdleDespawnTime increased from the engine default
 * (10 seconds) to a configurable value (default 120 seconds). This prevents mobs
 * from despawning mid-combat when the NPC's AI temporarily loses its target.
 * The HC_Threat plugin's ThreatTargetSystem continuously re-assigns targets for
 * NPCs with active threat, but the default 10s window is too short -- if the NPC
 * loses line-of-sight or pathfinding fails even briefly, the despawn timer can
 * expire before the target is re-assigned.
 */
public class SpawnScalingPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PLUGIN = "HC_SpawnScaling";

    public SpawnScalingPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Map<String, SettingDef> defaults = new LinkedHashMap<>();
        defaults.put("maxPlayersToScale", new SettingDef("20", "INT", "Maximum player count for linear spawn scaling curve"));
        defaults.put("mobDensityMultiplier", new SettingDef("1.0", "FLOAT", "Mob density multiplier for hostile beacons (2.0 = twice as many hostile mobs). Requires restart."));
        defaults.put("passiveDensityMultiplier", new SettingDef("0.5", "FLOAT", "Mob density multiplier for passive/ambient beacons (fish, animals). 0.5 = half scaling. Requires restart."));
        defaults.put("hostileIdleDespawnTime", new SettingDef("120.0", "FLOAT", "Seconds a hostile NPC must be idle (no target) before despawning. Engine default is 10s which causes mid-combat despawns. Requires restart."));
        HC_CoreAPI.registerDefaults(PLUGIN, defaults);
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

            int maxPlayers = HC_CoreAPI.getSettingInt(PLUGIN, "maxPlayersToScale", 20);
            double hostileDensity = HC_CoreAPI.getSettingDouble(PLUGIN, "mobDensityMultiplier", 1.0);
            if (hostileDensity < 1.0) hostileDensity = 1.0;
            double passiveDensity = HC_CoreAPI.getSettingDouble(PLUGIN, "passiveDensityMultiplier", 0.5);
            if (passiveDensity < 0.0) passiveDensity = 0.0;
            double hostileIdleDespawnTime = HC_CoreAPI.getSettingDouble(PLUGIN, "hostileIdleDespawnTime", 120.0);
            if (hostileIdleDespawnTime < 10.0) hostileIdleDespawnTime = 10.0;

            int hostileCount = 0;
            int passiveCount = 0;
            int skipCount = 0;
            int despawnTimeCount = 0;

            for (Map.Entry<String, BeaconNPCSpawn> entry : new ArrayList<>(beacons.entrySet())) {
                String beaconId = entry.getKey();
                BeaconNPCSpawn beacon = entry.getValue();

                // Determine if this beacon spawns hostile NPCs.
                // Beacons with NPCSpawnState (e.g. "Chase") are hostile -- NPCs
                // actively target players and despawn when idle without a target.
                // Beacons without NPCSpawnState are passive/ambient -- fish, animals,
                // wandering NPCs that persist indefinitely and fill spawn slots.
                boolean isHostile = beacon.getNpcSpawnState() != null;

                // HYC-167: Increase idle despawn time for hostile beacons to prevent
                // mid-combat despawns. The engine default is 10 seconds, which is too
                // short -- if an NPC briefly loses its target (pathfinding failure,
                // line-of-sight break, target slot cleared during world tick), the
                // despawn timer expires before the HC_Threat ThreatTargetSystem can
                // re-assign the target. Only modify beacons still using the default
                // value to avoid overriding intentionally configured values.
                if (isHostile && beacon.getNpcIdleDespawnTimeSeconds() <= 10.0) {
                    if (injectIdleDespawnTime(beacon, hostileIdleDespawnTime)) {
                        despawnTimeCount++;
                        LOGGER.atFine().log("[SpawnScaling] Increased idle despawn time for %s: %.0fs -> %.0fs",
                            beaconId, 10.0, hostileIdleDespawnTime);
                    }
                }

                // Check if already has a scaling curve
                if (beacon.getMaxSpawnsScalingCurve() != null) {
                    skipCount++;
                    continue;
                }

                int baseMaxSpawns = beacon.getMaxSpawnedNpcs();
                double density = isHostile ? hostileDensity : passiveDensity;

                // Curve formula: total = base + curve.computeY(playerCount)
                //
                // Without density (D=1): total = base * playerCount
                //   yMin = 0, yMax = base * (maxPlayers - 1)
                //
                // With density (D>1): total = D * base * playerCount
                //   yMin = base * (D - 1)          -- bonus even at 1 player
                //   yMax = base * (D * maxPlayers - 1)
                //
                // For passive beacons with D<1, the curve reduces per-player scaling.
                // At D=0.5 with 20 players: total = 0.5 * base * 20 = 10 * base
                // instead of 20 * base, keeping passive mob counts much lower.
                double yMin = baseMaxSpawns * (density - 1.0);
                double yMax = baseMaxSpawns * (density * maxPlayers - 1.0);

                // Clamp yMin to 0 -- negative yMin would reduce spawns below base
                // for single-player, which we don't want even for passive beacons
                if (yMin < 0.0) yMin = 0.0;

                ScaledXYResponseCurve curve = createLinearScalingCurve(
                    1.0, maxPlayers,  // XRange: player count
                    yMin, yMax        // YRange: additional spawns
                );

                if (curve != null && injectCurve(beacon, curve)) {
                    if (isHostile) {
                        hostileCount++;
                    } else {
                        passiveCount++;
                    }
                    LOGGER.atFine().log("[SpawnScaling] Injected curve for %s (base=%d, %s, density=%.1f, yRange=[%.0f, %.0f])",
                        beaconId, baseMaxSpawns, isHostile ? "hostile" : "passive", density, yMin, yMax);
                }
            }

            LOGGER.atInfo().log("[SpawnScaling] Injection complete: %d hostile + %d passive beacons modified, %d skipped (already had curves), hostileDensity=%.1fx, passiveDensity=%.1fx, %d hostile beacons got extended despawn time (%.0fs)",
                hostileCount, passiveCount, skipCount, hostileDensity, passiveDensity, despawnTimeCount, hostileIdleDespawnTime);

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

    /**
     * Injects a new idle despawn time into a beacon spawn using reflection.
     *
     * HYC-167: The engine's BeaconSpawnController reads npcIdleDespawnTimeSeconds
     * from the BeaconNPCSpawn asset at initialization. The ControllerTick system
     * then uses this value as the timeout threshold: when a hostile NPC has no
     * target and is not in a busy state, a per-entity timer accumulates delta-time.
     * Once the timer exceeds npcIdleDespawnTimeSeconds, the NPC is despawned.
     *
     * The default value is 10 seconds, which is far too aggressive for high-level
     * mobs that take minutes to kill. If the NPC's AI temporarily loses its target
     * (common during long fights due to pathfinding resets, line-of-sight breaks,
     * or target slot clearing during world ticks), 10 seconds is not enough buffer
     * for the HC_Threat ThreatTargetSystem to re-assign the player as the target.
     */
    private boolean injectIdleDespawnTime(BeaconNPCSpawn beacon, double seconds) {
        try {
            Field field = BeaconNPCSpawn.class.getDeclaredField("npcIdleDespawnTimeSeconds");
            field.setAccessible(true);
            field.setDouble(beacon, seconds);
            return true;
        } catch (Exception e) {
            LOGGER.atWarning().log("[SpawnScaling] Failed to inject idle despawn time into beacon: %s", e.getMessage());
            return false;
        }
    }
}
