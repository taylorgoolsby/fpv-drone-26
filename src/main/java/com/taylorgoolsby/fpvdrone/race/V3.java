package com.taylorgoolsby.fpvdrone.race;

/**
 * Tiny immutable float vector used by the gate math. It mirrors the jME3
 * Vector3f semantics the 1.16.5 code relied on (immutable ops, zero-safe
 * normalize, clamped acos) so the ported algorithms behave identically.
 *
 * Free of Minecraft imports.
 */
public final class V3 {
    public static final V3 ZERO = new V3(0, 0, 0);

    public final float x, y, z;

    public V3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public V3 add(V3 o) { return new V3(x + o.x, y + o.y, z + o.z); }

    public V3 sub(V3 o) { return new V3(x - o.x, y - o.y, z - o.z); }

    public V3 mul(float s) { return new V3(x * s, y * s, z * s); }

    public float dot(V3 o) { return x * o.x + y * o.y + z * o.z; }

    public V3 cross(V3 o) {
        return new V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public float lengthSquared() { return x * x + y * y + z * z; }

    public float length() { return (float) Math.sqrt(lengthSquared()); }

    /** Component of this vector along other (jME3 semantics: NaN if other is a zero vector). */
    public V3 project(V3 other) {
        float n = dot(other);
        float d = other.lengthSquared();
        return other.mul(n / d);
    }

    /** Zero-safe: a zero vector stays zero (like jME3, unlike JOML). */
    public V3 normalized() {
        float l = lengthSquared();
        if (l == 1f || l == 0f) return this;
        float inv = 1f / (float) Math.sqrt(l);
        return new V3(x * inv, y * inv, z * inv);
    }

    /** Angle in radians between two (assumed normalized) vectors. */
    public float angleBetween(V3 o) {
        return acosClamped(dot(o));
    }

    public static float acosClamped(float v) {
        if (-1.0f < v) {
            if (v < 1.0f) return (float) Math.acos(v);
            return 0.0f;
        }
        return (float) Math.PI;
    }

    @Override
    public String toString() { return "(" + x + ", " + y + ", " + z + ")"; }
}