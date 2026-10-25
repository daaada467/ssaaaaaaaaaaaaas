package com.veritymod.entity;

import com.veritymod.sound.ModSounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class VerityEntity extends PassiveEntity {
    private static final TrackedData<Boolean> IN_BOX = DataTracker.registerData(VerityEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> MOUTH_OPEN = DataTracker.registerData(VerityEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    /** Network id of the player carrying Verity, or -1. */
    private static final TrackedData<Integer> CARRIER_ID = DataTracker.registerData(VerityEntity.class, TrackedDataHandlerRegistry.INTEGER);

    /** Length of the client-side "jump out of the box" animation, in ticks. */
    public static final int RELEASE_ANIM_TICKS = 36;
    /** Visual size of Verity while carried. */
    public static final float CARRY_SCALE = 0.5f;

    private int boxJumpCooldown;
    private int speakTicks;
    private int mouthTimer;
    private int greetingAge;
    private int serverReleaseTicks;
    @Nullable private UUID carrierUuid;

    // physics (server)
    private double airVelY;
    private boolean wasOnGround = true;

    // visuals (client)
    private int releaseAge = -1;
    private int landAge = -1000;
    private float landAmp;
    private double clientFallSpeed;
    private boolean clientWasOnGround = true;

    public VerityEntity(EntityType<? extends VerityEntity> type, World world) {
        super(type, world);
        setInvulnerable(true);
        setPersistent();
    }

    public static DefaultAttributeContainer.Builder createVerityAttributes() {
        return createMobAttributes()
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.28)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(IN_BOX, true);
        builder.add(MOUTH_OPEN, false);
        builder.add(CARRIER_ID, -1);
    }

    @Override
    protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(1, new FollowNearestPlayerGoal(this));
    }

    // ------------------------------------------------------------------ state

    public boolean isInBox() { return dataTracker.get(IN_BOX); }
    public boolean isMouthOpen() { return dataTracker.get(MOUTH_OPEN); }
    public boolean isCarried() { return dataTracker.get(CARRIER_ID) >= 0; }
    public int getCarrierId() { return dataTracker.get(CARRIER_ID); }

    @Nullable
    public Entity getCarrier() {
        int id = getCarrierId();
        return id < 0 ? null : getWorld().getEntityById(id);
    }

    /** Ticks since the box was opened (client), or -1 when no animation is playing. */
    public float getReleaseAnim(float tickDelta) {
        if (releaseAge < 0) return -1f;
        float a = age - releaseAge + tickDelta;
        return a > RELEASE_ANIM_TICKS ? -1f : a;
    }

    /** Ticks since the last hard landing (client) and its strength, for squash. */
    public float getLandAnim(float tickDelta) { return age - landAge + tickDelta; }
    public float getLandAmp() { return landAmp; }

    /** Where a carried Verity's centre is: in front of the carrier, low and to the right. */
    public static Vec3d carryPos(PlayerEntity p, float tickDelta) {
        Vec3d eye = p.getCameraPosVec(tickDelta);
        Vec3d fwd = Vec3d.fromPolar(0.0f, p.getYaw(tickDelta));
        Vec3d right = new Vec3d(-fwd.z, 0.0, fwd.x);
        return eye.add(fwd.multiply(0.75)).add(right.multiply(0.35)).add(0.0, -0.6, 0.0);
    }

    @Nullable
    public static VerityEntity findCarriedBy(PlayerEntity player) {
        List<VerityEntity> list = player.getWorld().getEntitiesByClass(VerityEntity.class,
                player.getBoundingBox().expand(4.0), v -> v.getCarrierId() == player.getId());
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        // Only animate a live opening, not an already-open Verity that just came into view.
        if (getWorld().isClient && IN_BOX.equals(data) && !isInBox() && age > 2) {
            releaseAge = age;
        }
    }

    private void releaseFromBox(PlayerEntity player) {
        if (!isInBox() || getWorld().isClient) return;
        dataTracker.set(IN_BOX, false);
        // The merged greeting is 7.127 seconds long at 20 ticks per second.
        speakTicks = 143;
        greetingAge = 0;
        mouthTimer = 0;
        serverReleaseTicks = RELEASE_ANIM_TICKS;
        dataTracker.set(MOUTH_OPEN, false);
        getWorld().playSound(null, getX(), getY(), getZ(), ModSounds.VERITY_GREETING, SoundCategory.NEUTRAL, 1.0f, 1.0f);
    }

    // --------------------------------------------------------- carry & place

    private void pickUp(PlayerEntity player) {
        carrierUuid = player.getUuid();
        dataTracker.set(CARRIER_ID, player.getId());
        getNavigation().stop();
        setNoGravity(true);
        noClip = true;
        setVelocity(Vec3d.ZERO);
        getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 0.8f, 0.8f);
    }

    /** Puts Verity down at the given bottom-centre position. */
    public void placeDown(Vec3d pos) {
        if (getWorld().isClient) return;
        carrierUuid = null;
        dataTracker.set(CARRIER_ID, -1);
        setNoGravity(false);
        noClip = false;
        refreshPositionAndAngles(pos.x, pos.y + 0.05, pos.z, getYaw(), 0.0f);
        setVelocity(0.0, -0.1, 0.0);
        fallDistance = 0.0f;
        airVelY = 0.0;
        getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.NEUTRAL, 1.0f, 1.1f);
    }

    private void dropAtCurrentPosition() {
        placeDown(getPos());
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (isInBox()) {
            releaseFromBox(player);
            return ActionResult.SUCCESS;
        }
        if (!isCarried()) {
            if (!getWorld().isClient) {
                if (serverReleaseTicks > 0 || findCarriedBy(player) != null) return ActionResult.PASS;
                pickUp(player);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    // ------------------------------------------------- damage & knockback

    @Override
    public boolean damage(DamageSource source, float amount) {
        // Only /kill and the void can remove Verity.
        if (source.isOf(DamageTypes.GENERIC_KILL) || source.isOf(DamageTypes.OUT_OF_WORLD)) {
            if (isCarried()) dropAtCurrentPosition();
            return super.damage(source, amount);
        }
        if (getWorld().isClient || isCarried()) return false;

        if (source.getAttacker() instanceof PlayerEntity player && isInBox()) {
            releaseFromBox(player);
            return false;
        }
        // No damage, but a real, bouncy push away from whatever hit her.
        Entity pusher = source.getSource() != null ? source.getSource() : source.getAttacker();
        if (pusher != null && !isInBox()) {
            double dx = pusher.getX() - getX();
            double dz = pusher.getZ() - getZ();
            float strength = 0.55f;
            if (pusher.isSprinting()) strength += 0.45f;
            takeKnockback(strength, dx, dz);
            setVelocity(getVelocity().add(0.0, 0.12, 0.0));
            velocityModified = true;
            getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_SLIME_JUMP_SMALL, SoundCategory.NEUTRAL, 0.8f, 1.2f);
        }
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !(source.isOf(DamageTypes.GENERIC_KILL) || source.isOf(DamageTypes.OUT_OF_WORLD));
    }

    @Override
    public boolean isPushable() { return !isCarried(); }

    @Override
    public boolean canHit() { return !isCarried() && super.canHit(); }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) { return false; }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false;
    }

    // ----------------------------------------------------------------- tick

    @Override
    public void tick() {
        super.tick();
        lookAtNearestPlayer();

        if (getWorld().isClient) {
            clientTick();
            return;
        }

        if (isCarried()) {
            PlayerEntity carrier = carrierUuid == null ? null : getWorld().getPlayerByUuid(carrierUuid);
            if (carrier == null || !carrier.isAlive() || carrier.isSpectator()) {
                dropAtCurrentPosition();
            } else {
                Vec3d c = carryPos(carrier, 1.0f);
                setPosition(c.x, c.y - 0.45 * CARRY_SCALE, c.z);
                setVelocity(Vec3d.ZERO);
                fallDistance = 0.0f;
            }
        } else {
            bouncePhysics();
        }

        if (serverReleaseTicks > 0) serverReleaseTicks--;

        if (isInBox()) {
            if (isOnGround() && boxJumpCooldown-- <= 0) {
                setVelocity(getVelocity().x, 0.42, getVelocity().z);
                velocityDirty = true;
                boxJumpCooldown = 18;
            }
        } else if (speakTicks > 0) {
            greetingAge++;
            speakTicks--;
            if (isSpeechTick(greetingAge)) {
                if (++mouthTimer >= 3) {
                    mouthTimer = 0;
                    dataTracker.set(MOUTH_OPEN, !isMouthOpen());
                }
            } else {
                mouthTimer = 0;
                dataTracker.set(MOUTH_OPEN, false);
            }
            if (speakTicks == 0) dataTracker.set(MOUTH_OPEN, false);
        }
    }

    /** A rubber ball: hard landings bounce back with ~55% of the fall speed. */
    private void bouncePhysics() {
        if (!isOnGround()) {
            airVelY = getVelocity().y;
        } else if (!wasOnGround && !isInBox() && airVelY < -0.25) {
            double up = -airVelY * 0.55;
            setVelocity(getVelocity().x * 0.85, up, getVelocity().z * 0.85);
            velocityModified = true;
            float vol = (float) MathHelper.clamp(-airVelY, 0.3, 1.0);
            getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.NEUTRAL, vol, 1.2f);
            airVelY = 0.0;
        }
        wasOnGround = isOnGround();
    }

    /** Runs on both sides every tick so the face always points at the player. */
    private void lookAtNearestPlayer() {
        PlayerEntity target = getWorld().getClosestPlayer(getX(), getY(), getZ(), 64.0, EntityPredicates.EXCEPT_SPECTATOR);
        if (target == null) return;
        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        double dy = target.getEyeY() - (getY() + 0.45);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
        setYaw(yaw);
        setBodyYaw(yaw);
        setHeadYaw(yaw);
        setPitch(MathHelper.clamp(pitch, -40.0f, 40.0f));
    }

    private void clientTick() {
        // landing squash detection
        double vy = getY() - prevY;
        if (!isOnGround()) {
            clientFallSpeed = vy;
        } else if (!clientWasOnGround && clientFallSpeed < -0.15 && !isCarried()) {
            landAge = age;
            landAmp = (float) MathHelper.clamp(-clientFallSpeed * 0.8, 0.08, 0.35);
            clientFallSpeed = 0.0;
        }
        clientWasOnGround = isOnGround();

        if (releaseAge < 0) return;
        int a = age - releaseAge;
        if (a == 2) {
            localSound(SoundEvents.ENTITY_SHEEP_SHEAR, 1.0f, 1.2f);
        } else if (a == 4) {
            localSound(SoundEvents.ENTITY_ITEM_PICKUP, 1.0f, 0.7f);
            for (int i = 0; i < 14; i++) {
                getWorld().addParticle(ParticleTypes.POOF, getX(), getY() + 0.7, getZ(),
                        (random.nextDouble() - 0.5) * 0.25, 0.12 + random.nextDouble() * 0.1, (random.nextDouble() - 0.5) * 0.25);
            }
            for (int i = 0; i < 8; i++) {
                getWorld().addParticle(ParticleTypes.END_ROD, getX(), getY() + 0.8, getZ(),
                        (random.nextDouble() - 0.5) * 0.2, 0.15 + random.nextDouble() * 0.1, (random.nextDouble() - 0.5) * 0.2);
            }
        } else if (a == 20) {
            localSound(SoundEvents.BLOCK_WOOL_BREAK, 1.0f, 0.9f);
            for (int i = 0; i < 24; i++) {
                getWorld().addParticle(ParticleTypes.POOF,
                        getX() + (random.nextDouble() - 0.5), getY() + random.nextDouble() * 0.7,
                        getZ() + (random.nextDouble() - 0.5) * 0.8, 0.0, 0.03, 0.0);
            }
        }
    }

    private void localSound(SoundEvent sound, float volume, float pitch) {
        getWorld().playSound(getX(), getY(), getZ(), sound, SoundCategory.NEUTRAL, volume, pitch, false);
    }

    private static boolean isSpeechTick(int tick) {
        return (tick >= 10 && tick <= 66)
                || (tick >= 88 && tick <= 114)
                || (tick >= 124 && tick <= 140);
    }

    // ------------------------------------------------------------------ save

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("VerityInBox", isInBox());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("VerityInBox")) dataTracker.set(IN_BOX, nbt.getBoolean("VerityInBox"));
        // A carried Verity is never saved as carried: it is simply put down where it was.
        dataTracker.set(CARRIER_ID, -1);
        setNoGravity(false);
        noClip = false;
    }

    @Override
    public PassiveEntity createChild(ServerWorld world, PassiveEntity entity) { return null; }

    // ------------------------------------------------------------- follow AI

    /** Walks after the nearest player, keeping ~2.5 blocks of distance. */
    static class FollowNearestPlayerGoal extends Goal {
        private final VerityEntity verity;
        @Nullable private PlayerEntity target;
        private int recalc;

        FollowNearestPlayerGoal(VerityEntity verity) {
            this.verity = verity;
            setControls(EnumSet.of(Goal.Control.MOVE));
        }

        private boolean busy() {
            return verity.isInBox() || verity.isCarried() || verity.serverReleaseTicks > 0;
        }

        @Override
        public boolean canStart() {
            if (busy()) return false;
            PlayerEntity p = verity.getWorld().getClosestPlayer(verity.getX(), verity.getY(), verity.getZ(), 48.0, EntityPredicates.EXCEPT_SPECTATOR);
            if (p == null || verity.squaredDistanceTo(p) < 9.0) return false;
            target = p;
            return true;
        }

        @Override
        public boolean shouldContinue() {
            return !busy() && target != null && target.isAlive() && !target.isSpectator()
                    && verity.squaredDistanceTo(target) > 5.0;
        }

        @Override
        public void start() { recalc = 0; }

        @Override
        public void stop() {
            target = null;
            verity.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (target == null || --recalc > 0) return;
            recalc = 10;
            double d2 = verity.squaredDistanceTo(target);
            if (d2 > 28.0 * 28.0 && target.isOnGround()) {
                // Too far behind: catch up like a pet.
                verity.getNavigation().stop();
                verity.refreshPositionAndAngles(target.getX(), target.getY(), target.getZ(), verity.getYaw(), 0.0f);
                return;
            }
            verity.getNavigation().startMovingTo(target, d2 > 100.0 ? 1.5 : 1.0);
        }
    }
}
