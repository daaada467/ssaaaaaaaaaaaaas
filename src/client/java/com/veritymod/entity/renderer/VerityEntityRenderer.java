package com.veritymod.entity.renderer;

import com.veritymod.VerityMod;
import com.veritymod.entity.VerityEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * Verity: a smooth yellow 3D ball with a face built from geometry
 * (no textures with backgrounds, so no gray/orange halos or seams).
 * Box: a taped cardboard cuboid whose lid flaps burst open on release.
 */
public class VerityEntityRenderer extends EntityRenderer<VerityEntity> {
    private static final Identifier WHITE = Identifier.of(VerityMod.MOD_ID, "textures/entity/verity_white.png");
    private static final Identifier BOX = Identifier.of(VerityMod.MOD_ID, "textures/entity/verity_box.png");

    private static final float R = 0.45f;             // ball radius (0.9 block diameter)
    private static final int LAT = 24, LON = 40;      // ball smoothness
    private static final float[][] SPHERE = buildSphere();

    private static final int[] YELLOW = {255, 222, 0};
    private static final int[] BLACK = {20, 20, 20};
    private static final int[] TONGUE = {255, 238, 110};

    public VerityEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.45f;
        this.shadowOpacity = 0.6f;
    }

    @Override
    public Identifier getTexture(VerityEntity entity) {
        return entity.isInBox() ? BOX : WHITE;
    }

    @Override
    public void render(VerityEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider providers, int light) {
        float lookYaw = MathHelper.lerpAngleDegrees(tickDelta, entity.prevHeadYaw, entity.headYaw);

        if (entity.isInBox()) {
            matrices.push();
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-lookYaw));
            renderBox(matrices, providers.getBuffer(RenderLayer.getEntityCutoutNoCull(BOX)), light, 0f);
            matrices.pop();
            super.render(entity, yaw, tickDelta, matrices, providers, light);
            return;
        }

        float anim = entity.getReleaseAnim(tickDelta);
        float t = entity.age + tickDelta;

        // ---- the box during the release animation: shake, flaps burst open, then it vanishes
        if (anim >= 0f && anim < 20f) {
            matrices.push();
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-lookYaw));
            if (anim < 3f) {
                float d = 1f - anim / 3f;
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.sin(anim * 5f) * 9f * d));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.cos(anim * 4f) * 5f * d));
                matrices.scale(1f, 1f + 0.07f * MathHelper.sin(anim * 5f) * d, 1f);
            }
            if (anim > 17f) {
                float k = Math.max(0.001f, 1f - (anim - 17f) / 3f);
                matrices.scale(k, k, k);
            }
            float flap = anim < 2f ? 0f : easeOutBack(MathHelper.clamp((anim - 2f) / 3f, 0f, 1f)) * 125f;
            renderBox(matrices, providers.getBuffer(RenderLayer.getEntityCutoutNoCull(BOX)), light, Math.max(0.01f, flap));
            matrices.pop();
        }

        VertexConsumer vc = providers.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));
        float pitch = MathHelper.clamp(entity.getPitch(tickDelta), -40f, 40f) * 0.6f;

        // ---- carried in the player's hand
        if (entity.isCarried()) {
            Entity carrier = entity.getCarrier();
            if (carrier instanceof PlayerEntity player) {
                Vec3d c = VerityEntity.carryPos(player, tickDelta);
                double ex = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX());
                double ey = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY());
                double ez = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ());
                matrices.push();
                matrices.translate(c.x - ex, c.y - ey - (R + FOOT_LIFT) * VerityEntity.CARRY_SCALE, c.z - ez);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-lookYaw));
                matrices.scale(VerityEntity.CARRY_SCALE, VerityEntity.CARRY_SCALE, VerityEntity.CARRY_SCALE);
                // dangling feet that sway a little
                float sway = MathHelper.sin(t * 0.25f) * 0.04f;
                renderFeet(matrices.peek(), vc, light, sway, -sway, 0f, 0f);
                matrices.translate(0.0, FOOT_LIFT, 0.0);
                tiltAndDrawBall(matrices, vc, light, entity, pitch);
                matrices.pop();
                super.render(entity, yaw, tickDelta, matrices, providers, light);
                return;
            }
        }

        // ---- Verity: launch out of the box, land on its lid, hop down to the ground
        float y = 0.0f, sy = 1f, sxz = 1f, spin = 0f, grow = 1f;
        boolean visible = true;
        float bob = MathHelper.sin(t * 0.12f) * 0.02f;
        if (anim < 0f) {
            y += bob;
        } else if (anim < 4f) {
            visible = false;
        } else if (anim < 14f) {
            float s = (anim - 4f) / 10f;
            float h = 1.2f;
            y = MathHelper.lerp(s, 0.05f, BY) + 4f * h * s * (1f - s);
            float v = ((BY - 0.05f) + 4f * h * (1f - 2f * s)) / (4f * h);
            sy = 1f + 0.22f * Math.abs(v);
            sxz = 1f / (float) Math.sqrt(sy);
            spin = 360f * easeOutCubic(s);
            grow = MathHelper.clamp(0.55f + s * 1.2f, 0f, 1f);
        } else if (anim < 20f) {
            y = BY;
            float k = anim - 14f;
            sy = wobble(k, 0.30f);
            sxz = 1f + (1f - sy) * 0.7f;
        } else if (anim < 24f) {
            float s = (anim - 20f) / 4f;
            y = BY + (0.0f - BY) * s * s;
            sy = 1f + 0.12f * s;
            sxz = 1f / (float) Math.sqrt(sy);
        } else {
            float k = anim - 24f;
            sy = wobble(k, 0.22f);
            sxz = 1f + (1f - sy) * 0.7f;
            y = bob * MathHelper.clamp(k / 12f, 0f, 1f);
        }

        // landing squash after falls, bounces and knockback
        float land = entity.getLandAnim(tickDelta);
        if (anim < 0f && land >= 0f && land < 14f) {
            float w = wobble(land, entity.getLandAmp());
            sy *= w;
            sxz *= 1f + (1f - w) * 0.7f;
        }

        if (visible) {
            // walking: alternating feet, side-to-side waddle and a small step bounce
            float limbPos = entity.limbAnimator.getPos(tickDelta);
            float limbSpeed = MathHelper.clamp(entity.limbAnimator.getSpeed(tickDelta), 0f, 1f);
            float phase = limbPos * 0.6662f;
            float stepL = MathHelper.cos(phase) * 0.16f * limbSpeed;
            float stepR = -stepL;
            float liftL = Math.max(0f, MathHelper.sin(phase)) * 0.07f * limbSpeed;
            float liftR = Math.max(0f, -MathHelper.sin(phase)) * 0.07f * limbSpeed;
            float stepBounce = Math.abs(MathHelper.sin(phase)) * 0.05f * limbSpeed;
            float waddle = MathHelper.sin(phase) * 7f * limbSpeed;

            matrices.push();
            matrices.translate(0.0, y, 0.0);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-lookYaw - spin));
            matrices.scale(grow, grow, grow);
            renderFeet(matrices.peek(), vc, light, stepL, stepR, liftL, liftR);

            matrices.translate(0.0, FOOT_LIFT + stepBounce, 0.0);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(waddle));
            // squash & stretch around the bottom of the ball
            matrices.scale(sxz, sy, sxz);
            tiltAndDrawBall(matrices, vc, light, entity, pitch);
            matrices.pop();
        }

        super.render(entity, yaw, tickDelta, matrices, providers, light);
    }

    private static void tiltAndDrawBall(MatrixStack matrices, VertexConsumer vc, int light,
                                        VerityEntity entity, float pitch) {
        matrices.push();
        matrices.translate(0.0, R, 0.0);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        if (entity.isMouthOpen()) {
            matrices.scale(1.03f, 0.97f, 1.03f);
        }
        matrices.translate(0.0, -R, 0.0);
        renderVerity(matrices.peek(), vc, light, entity.isMouthOpen());
        matrices.pop();
    }

    // ------------------------------------------------------------------- feet

    private static final float FOOT_LIFT = 0.07f;
    private static final int[] FOOT = {235, 168, 0};

    private static void renderFeet(MatrixStack.Entry e, VertexConsumer vc, int light,
                                   float stepL, float stepR, float liftL, float liftR) {
        ellipsoid(e, vc, light, -0.17f, 0.055f + liftL, 0.04f + stepL, 0.10f, 0.055f, 0.15f, FOOT);
        ellipsoid(e, vc, light, 0.17f, 0.055f + liftR, 0.04f + stepR, 0.10f, 0.055f, 0.15f, FOOT);
    }

    private static void ellipsoid(MatrixStack.Entry e, VertexConsumer vc, int light,
                                  float cx, float cy, float cz, float rx, float ry, float rz, int[] col) {
        for (int i = 0; i < LAT; i += 2) {
            for (int j = 0; j < LON; j += 2) {
                ellipsoidVertex(e, vc, light, SPHERE[i * (LON + 1) + j], cx, cy, cz, rx, ry, rz, col);
                ellipsoidVertex(e, vc, light, SPHERE[(i + 2) * (LON + 1) + j], cx, cy, cz, rx, ry, rz, col);
                ellipsoidVertex(e, vc, light, SPHERE[(i + 2) * (LON + 1) + j + 2], cx, cy, cz, rx, ry, rz, col);
                ellipsoidVertex(e, vc, light, SPHERE[i * (LON + 1) + j + 2], cx, cy, cz, rx, ry, rz, col);
            }
        }
    }

    private static void ellipsoidVertex(MatrixStack.Entry e, VertexConsumer vc, int light, float[] n,
                                        float cx, float cy, float cz, float rx, float ry, float rz, int[] col) {
        float nx = n[0] / rx, ny = n[1] / ry, nz = n[2] / rz;
        float len = MathHelper.sqrt(nx * nx + ny * ny + nz * nz);
        vertex(e, vc, cx + n[0] * rx, cy + n[1] * ry, cz + n[2] * rz, nx / len, ny / len, nz / len, 0.5f, 0.5f, col, light);
    }

    private static float wobble(float k, float amp) {
        return 1f - amp * (float) Math.exp(-k * 0.45f) * MathHelper.cos(k * 1.5f);
    }

    private static float easeOutCubic(float x) {
        float i = 1f - x;
        return 1f - i * i * i;
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        float m = x - 1f;
        return 1f + c3 * m * m * m + c1 * m * m;
    }

    // ------------------------------------------------------------------ Verity

    private static float[][] buildSphere() {
        float[][] pts = new float[(LAT + 1) * (LON + 1)][];
        for (int i = 0; i <= LAT; i++) {
            double th = Math.PI * i / LAT;
            for (int j = 0; j <= LON; j++) {
                double ph = 2 * Math.PI * j / LON;
                float nx = (float) (Math.sin(th) * Math.sin(ph));
                float ny = (float) Math.cos(th);
                float nz = (float) (Math.sin(th) * Math.cos(ph));
                pts[i * (LON + 1) + j] = new float[]{nx, ny, nz};
            }
        }
        return pts;
    }

    private static void renderVerity(MatrixStack.Entry e, VertexConsumer vc, int light, boolean open) {
        // Body
        for (int i = 0; i < LAT; i++) {
            for (int j = 0; j < LON; j++) {
                float[] a = SPHERE[i * (LON + 1) + j];
                float[] b = SPHERE[(i + 1) * (LON + 1) + j];
                float[] c = SPHERE[(i + 1) * (LON + 1) + j + 1];
                float[] d = SPHERE[i * (LON + 1) + j + 1];
                ballVertex(e, vc, a, R, YELLOW, light);
                ballVertex(e, vc, b, R, YELLOW, light);
                ballVertex(e, vc, c, R, YELLOW, light);
                ballVertex(e, vc, d, R, YELLOW, light);
            }
        }

        float face = R + 0.004f;
        // Eyes: small vertical ovals, close together (like the original 2D face)
        ellipse(e, vc, light, -0.13f, 0.17f, 0.042f, 0.075f, face, BLACK);
        ellipse(e, vc, light, 0.13f, 0.17f, 0.042f, 0.075f, face, BLACK);

        if (!open) {
            // Closed: thin smile, thicker in the middle
            final float w = 0.32f;
            patch(e, vc, light, -w, w,
                    a -> smile(a, w) + smileHalf(a, w),
                    a -> smile(a, w) - smileHalf(a, w),
                    face, BLACK);
        } else {
            // Open: "D"-shaped mouth with a tongue
            final float w = 0.30f;
            patch(e, vc, light, -w, w,
                    a -> -0.07f + 0.035f * (a / w) * (a / w),
                    a -> -0.07f - 0.20f * root(a / w),
                    face, BLACK);
            ellipse(e, vc, light, 0.0f, -0.225f, 0.12f, 0.042f, R + 0.008f, TONGUE);
        }
    }

    private static float smile(float a, float w) {
        float k = a / w;
        return -0.10f - 0.10f * (1 - k * k);
    }

    private static float smileHalf(float a, float w) {
        return 0.012f + 0.012f * root(a / w);
    }

    private static float root(float k) {
        return (float) Math.sqrt(Math.max(0f, 1f - k * k));
    }

    private interface Curve {
        float at(float a);
    }

    private static void ellipse(MatrixStack.Entry e, VertexConsumer vc, int light,
                                float ca, float cb, float w, float h, float radius, int[] col) {
        patch(e, vc, light, ca - w, ca + w,
                a -> cb + h * root((a - ca) / w),
                a -> cb - h * root((a - ca) / w),
                radius, col);
    }

    /** Fills the region between two curves on the sphere, in (yaw a, pitch b) angles. */
    private static void patch(MatrixStack.Entry e, VertexConsumer vc, int light,
                              float aMin, float aMax, Curve top, Curve bottom, float radius, int[] col) {
        final int cols = 20, rows = 4;
        for (int i = 0; i < cols; i++) {
            float a0 = aMin + (aMax - aMin) * i / cols;
            float a1 = aMin + (aMax - aMin) * (i + 1) / cols;
            float t0 = top.at(a0), b0 = bottom.at(a0);
            float t1 = top.at(a1), b1 = bottom.at(a1);
            for (int j = 0; j < rows; j++) {
                float f0 = (float) j / rows, f1 = (float) (j + 1) / rows;
                surfaceVertex(e, vc, a0, MathHelper.lerp(f0, t0, b0), radius, col, light);
                surfaceVertex(e, vc, a0, MathHelper.lerp(f1, t0, b0), radius, col, light);
                surfaceVertex(e, vc, a1, MathHelper.lerp(f1, t1, b1), radius, col, light);
                surfaceVertex(e, vc, a1, MathHelper.lerp(f0, t1, b1), radius, col, light);
            }
        }
    }

    private static void surfaceVertex(MatrixStack.Entry e, VertexConsumer vc, float a, float b,
                                      float radius, int[] col, int light) {
        float cb = MathHelper.cos(b);
        float nx = MathHelper.sin(a) * cb, ny = MathHelper.sin(b), nz = MathHelper.cos(a) * cb;
        vertex(e, vc, nx * radius, R + ny * radius, nz * radius, nx, ny, nz, 0.5f, 0.5f, col, light);
    }

    private static void ballVertex(MatrixStack.Entry e, VertexConsumer vc, float[] n, float radius,
                                   int[] col, int light) {
        vertex(e, vc, n[0] * radius, R + n[1] * radius, n[2] * radius, n[0], n[1], n[2], 0.5f, 0.5f, col, light);
    }

    // --------------------------------------------------------------------- Box

    private static final float BX = 0.48f, BY = 0.66f, BZ = 0.40f;
    private static final float WALL = 0.03f;
    private static final float THIRD = 1f / 3f;
    private static final int[] FULL = {255, 255, 255};
    private static final int[] INNER = {120, 92, 62};

    /**
     * Cardboard box. flapDegrees == 0 draws a closed, taped lid; anything above
     * zero draws an open box (inner walls) with two lid flaps rotated outwards.
     */
    private static void renderBox(MatrixStack matrices, VertexConsumer vc, int light, float flapDegrees) {
        MatrixStack.Entry e = matrices.peek();
        // atlas: [0..1/3] long side, [1/3..2/3] short side with tape, [2/3..1] top with tape
        face(e, vc, light, FULL, 0, 0, 1, 0f, THIRD, 0f, 1f,
                -BX, BY, BZ, -BX, 0, BZ, BX, 0, BZ, BX, BY, BZ);
        face(e, vc, light, FULL, 0, 0, -1, 0f, THIRD, 0f, 1f,
                BX, BY, -BZ, BX, 0, -BZ, -BX, 0, -BZ, -BX, BY, -BZ);
        face(e, vc, light, FULL, 1, 0, 0, THIRD, 2 * THIRD, 0f, 1f,
                BX, BY, BZ, BX, 0, BZ, BX, 0, -BZ, BX, BY, -BZ);
        face(e, vc, light, FULL, -1, 0, 0, THIRD, 2 * THIRD, 0f, 1f,
                -BX, BY, -BZ, -BX, 0, -BZ, -BX, 0, BZ, -BX, BY, BZ);
        face(e, vc, light, FULL, 0, -1, 0, 0f, THIRD, 0f, 1f,
                -BX, 0, BZ, -BX, 0, -BZ, BX, 0, -BZ, BX, 0, BZ);

        if (flapDegrees <= 0f) {
            face(e, vc, light, FULL, 0, 1, 0, 2 * THIRD, 1f, 0f, 1f,
                    -BX, BY, -BZ, -BX, BY, BZ, BX, BY, BZ, BX, BY, -BZ);
            return;
        }

        // open box: darker inner walls, inner floor and the top rim
        float ix = BX - WALL, iz = BZ - WALL, fy = WALL;
        face(e, vc, light, INNER, 0, 0, -1, 0f, THIRD, 0f, 1f,
                -ix, BY, iz, -ix, fy, iz, ix, fy, iz, ix, BY, iz);
        face(e, vc, light, INNER, 0, 0, 1, 0f, THIRD, 0f, 1f,
                ix, BY, -iz, ix, fy, -iz, -ix, fy, -iz, -ix, BY, -iz);
        face(e, vc, light, INNER, -1, 0, 0, 0f, THIRD, 0f, 1f,
                ix, BY, iz, ix, fy, iz, ix, fy, -iz, ix, BY, -iz);
        face(e, vc, light, INNER, 1, 0, 0, 0f, THIRD, 0f, 1f,
                -ix, BY, -iz, -ix, fy, -iz, -ix, fy, iz, -ix, BY, iz);
        face(e, vc, light, INNER, 0, 1, 0, 0f, THIRD, 0f, 1f,
                -ix, fy, -iz, -ix, fy, iz, ix, fy, iz, ix, fy, -iz);
        float rimU0 = 0f, rimU1 = 0.02f;
        face(e, vc, light, FULL, 0, 1, 0, rimU0, rimU1, 0f, 1f,
                -BX, BY, iz, -BX, BY, BZ, BX, BY, BZ, BX, BY, iz);
        face(e, vc, light, FULL, 0, 1, 0, rimU0, rimU1, 0f, 1f,
                -BX, BY, -BZ, -BX, BY, -iz, BX, BY, -iz, BX, BY, -BZ);
        face(e, vc, light, FULL, 0, 1, 0, rimU0, rimU1, 0f, 1f,
                -BX, BY, -iz, -BX, BY, iz, -ix, BY, iz, -ix, BY, -iz);
        face(e, vc, light, FULL, 0, 1, 0, rimU0, rimU1, 0f, 1f,
                ix, BY, -iz, ix, BY, iz, BX, BY, iz, BX, BY, -iz);

        // flap hinged on the +Z edge (covers the +Z half of the lid)
        matrices.push();
        matrices.translate(0.0, BY, BZ);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(flapDegrees));
        MatrixStack.Entry f1 = matrices.peek();
        face(f1, vc, light, FULL, 0, 1, 0, 2 * THIRD, 1f, 0.5f, 1f,
                -BX, 0, -BZ, -BX, 0, 0, BX, 0, 0, BX, 0, -BZ);
        matrices.pop();

        // flap hinged on the -Z edge
        matrices.push();
        matrices.translate(0.0, BY, -BZ);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-flapDegrees));
        MatrixStack.Entry f2 = matrices.peek();
        face(f2, vc, light, FULL, 0, 1, 0, 2 * THIRD, 1f, 0f, 0.5f,
                -BX, 0, 0, -BX, 0, BZ, BX, 0, BZ, BX, 0, 0);
        matrices.pop();
    }

    /** Corners: top-left, bottom-left, bottom-right, top-right. */
    private static void face(MatrixStack.Entry e, VertexConsumer vc, int light, int[] col,
                             float nx, float ny, float nz, float u0, float u1, float v0, float v1,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3) {
        vertex(e, vc, x0, y0, z0, nx, ny, nz, u0, v0, col, light);
        vertex(e, vc, x1, y1, z1, nx, ny, nz, u0, v1, col, light);
        vertex(e, vc, x2, y2, z2, nx, ny, nz, u1, v1, col, light);
        vertex(e, vc, x3, y3, z3, nx, ny, nz, u1, v0, col, light);
    }

    // ------------------------------------------------------------------ common

    private static void vertex(MatrixStack.Entry e, VertexConsumer vc, float x, float y, float z,
                               float nx, float ny, float nz, float u, float v, int[] col, int light) {
        vc.vertex(e.getPositionMatrix(), x, y, z)
                .color(col[0], col[1], col[2], 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(e, nx, ny, nz);
    }
}
