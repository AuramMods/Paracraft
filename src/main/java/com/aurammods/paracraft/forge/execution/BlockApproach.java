package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Route to a usable work position instead of trying to occupy the target block. */
final class BlockApproach {
    record Stance(Vec3 feet, boolean jump) {}

    static final class Unreachable extends IllegalStateException {
        Unreachable(String message) { super(message); }
    }

    private Iterator<Stance> candidates;
    private Stance waypoint;
    private int jumpTicks;

    boolean tick(GameJob job, BlockPos target, BooleanSupplier reachable, Supplier<List<Stance>> nearby, String failure) {
        var actor = job.actor();
        var level = actor.serverLevel();
        var center = Vec3.atCenterOf(target);
        if (level.hasChunkAt(target) && reachable.getAsBoolean()) {
            job.stopMoving();
            reset();
            return true;
        }
        if (jumpTicks > 0) {
            if (++jumpTicks < 14 && !actor.onGround()) return false;
            jumpTicks = 0;
            waypoint = null;
        }
        if (candidates == null) {
            try {
                if ((!level.hasChunkAt(target) || actor.distanceToSqr(center) > 64) && !job.navigate(center, 5.5))
                    return false;
            } catch (IllegalStateException blockedRoute) {
                // The nearest work position can still be reachable when the center is blocked.
                job.stopMoving();
            }
            candidates = level.hasChunkAt(target) ? nearby.get().iterator() : List.<Stance>of().iterator();
        }
        if (waypoint == null) {
            if (!candidates.hasNext()) {
                job.stopMoving();
                reset();
                throw new Unreachable(failure);
            }
            waypoint = candidates.next();
        }
        try {
            if (!job.navigate(waypoint.feet(), 0.28)) return false;
        } catch (IllegalStateException unreachable) {
            job.stopMoving();
            waypoint = null;
            return false;
        }
        if (reachable.getAsBoolean()) {
            reset();
            return true;
        }
        if (waypoint.jump() && actor.onGround()) {
            actor.setDeltaMovement(0, 0.42, 0);
            jumpTicks = 1;
            return false;
        }
        waypoint = null;
        return false;
    }

    void reset() { candidates = null; waypoint = null; jumpTicks = 0; }

    static List<Stance> candidates(CompanionPlayer actor, BlockPos target, boolean allowJump, Predicate<Vec3> usableEye) {
        return stances(actor, target, true, allowJump, point -> usableEye.test(point.add(0, actor.getEyeHeight(), 0)));
    }

    static List<Stance> pickupCandidates(CompanionPlayer actor, BlockPos target, AABB item) {
        return stances(actor, target, false, false,
                point -> actor.getBoundingBox().move(point.subtract(actor.position())).inflate(0.85, 0.35, 0.85).intersects(item));
    }

    private static List<Stance> stances(CompanionPlayer actor, BlockPos target, boolean excludeTarget,
                                        boolean allowJump, Predicate<Vec3> usableFeet) {
        var level = actor.serverLevel();
        var candidates = new ArrayList<Stance>();
        var targetBox = new AABB(target);
        boolean flight = actor.getAbilities().mayfly && !actor.isPassenger();
        for (int y = -5; y <= 3; y++) for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) {
            BlockPos feet = target.offset(x, y, z);
            if (level.isOutsideBuildHeight(feet) || !level.hasChunkAt(feet) || !level.getWorldBorder().isWithinBounds(feet)) continue;
            Vec3 point = Vec3.atBottomCenterOf(feet);
            var box = actor.getBoundingBox().move(point.subtract(actor.position()));
            if ((excludeTarget && targetBox.intersects(box)) || !level.noCollision(actor, box)) continue;
            boolean supported = level.getBlockCollisions(actor, box.move(0, -0.15, 0)).iterator().hasNext();
            if (!flight && !supported && !PlayerPathEvaluator.water(level, feet) && !PlayerPathEvaluator.climbable(level, feet)) continue;
            if (usableFeet.test(point)) {
                candidates.add(new Stance(point, false));
            } else if (allowJump && supported && !flight) {
                var raised = box.move(0, 0.9, 0);
                if (!targetBox.intersects(raised) && level.noCollision(actor, raised) && usableFeet.test(point.add(0, 0.9, 0))) {
                    candidates.add(new Stance(point, true));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(stance -> actor.position().distanceToSqr(stance.feet())));
        return candidates;
    }
}
