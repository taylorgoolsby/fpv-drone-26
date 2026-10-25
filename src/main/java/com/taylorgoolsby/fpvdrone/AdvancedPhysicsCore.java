package com.taylorgoolsby.fpvdrone;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Blade-element drone physics, ported from the old (1.16) version of the mod.
 *
 * What it simulates, per physics substep (all SI units, 1 block = 1 m):
 *   - four BLDC motors (back-EMF, winding resistance that rises with
 *     temperature, heating and cooling),
 *   - four propellers, each split into radial sections that see their own
 *     airflow, angle of attack, lift and drag,
 *   - quadratic air drag on the airframe,
 *   - gravity.
 *
 * What it does NOT do: rotation. Attitude is still driven directly by the
 * sticks in DroneClientEvents (rate mode), and collisions are still resolved
 * by DroneState through player.move(). This class only turns
 * (throttle, orientation, velocity) into a new velocity.
 *
 * Pure math + JOML, no Minecraft imports.
 */
public class AdvancedPhysicsCore {

    // ---------------------------------------------------------------------
    // Drone build. Edit these to change how the drone flies.
    // ---------------------------------------------------------------------

    public static final float GRAVITY = 9.8f;           // m/s^2

    private static final float DRONE_MASS = 0.65f;      // kg, with battery
    private static final int BATTERY_CELLS = 4;
    private static final float MOTOR_KV_RPM_PER_VOLT = 2000f;

    private static final int BLADES = 3;
    private static final float PROP_RADIUS = 0.0635f;   // m (5 inch prop)
    private static final float PROP_PITCH = 0.10f;      // m per revolution
    private static final float PROP_WIDTH = 0.015f;     // m, blade chord
    private static final float BLADE_MASS = 0.0015f;    // kg per blade

    private static final float MOTOR_MASS = 0.033f;     // kg
    private static final float BELL_MASS = 0.012f;      // kg, rotating part
    private static final float MOTOR_WIDTH = 0.023f;    // m, stator diameter
    private static final float MOTOR_HEIGHT = 0.006f;   // m, stator height

    // Physical constants.
    private static final float AIR_DENSITY = 1.225f;            // kg/m^3
    private static final float DRONE_DENSITY = 1500f;           // kg/m^3
    private static final float DRAG_COEFFICIENT = 1.05f;
    private static final float VAC = 1.25663706e-6f;            // vacuum permeability
    private static final float INCHES_PER_METER = 39.3701f;
    private static final float RESISTANCE_PER_1000_FT = 25.7f;  // ohm, ~AWG 24 copper
    private static final float TEMP_COEFF = 0.00393f;           // copper, 1/K
    private static final float SPECIFIC_HEAT = 400f;            // J/(kg K)
    private static final float HEAT_TRANSFER = 50f;             // W/(m^2 K)

    private static final float REF_TEMP = 293.15f;              // K

    // Internal simulation settings.
    private static final float MAX_TIME_STEP = 1f / 128f;
    private static final long MAX_STEP_BUDGET_NANOS = 66_000_000L;
    private static final int PROP_SECTIONS = 5;

    /**
     * Throttle (0..1) at which the drone hovers, found by simulating the
     * motors and props at rest. Recomputed from the constants above, so it
     * stays correct when the build is changed.
     */
    public static final float HOVER_THROTTLE = findHoverThrottle();

    // ---------------------------------------------------------------------
    // Derived motor properties (computed once).
    // ---------------------------------------------------------------------

    private final float kv;               // rad/s per volt
    private final float refResistance;    // ohm
    private final float heatCap;          // J/K
    private final float motorSurfaceArea; // m^2
    private final float inertia;          // kg m^2
    private final float dragFactor;       // kg/m

    // ---------------------------------------------------------------------
    // State.
    // ---------------------------------------------------------------------

    private final float[] internalEnergy = new float[4];
    private final float[] temp = new float[4];
    private final float[] motorVel = new float[4];
    private final float[] motorPos = new float[4];

    // Working copies for the current step.
    private final Vector3f velocity = new Vector3f();
    private final Vector3f droneUp = new Vector3f(0, 1, 0);
    private final Vector3f droneLeft = new Vector3f(1, 0, 0);

    public AdvancedPhysicsCore() {
        kv = MOTOR_KV_RPM_PER_VOLT * (float) (2.0 * Math.PI / 60.0);

        float R = MOTOR_WIDTH * 0.5f;
        float poles = getPoles(MOTOR_WIDTH);
        float slots = getSlots(MOTOR_WIDTH);

        heatCap = SPECIFIC_HEAT * MOTOR_MASS;
        motorSurfaceArea = 2f * (float) Math.PI * R * MOTOR_HEIGHT
                + 2f * (float) Math.PI * R * R;
        refResistance = computeRefResistance(R, MOTOR_HEIGHT, kv, poles, slots);
        inertia = computeMotorInertia();

        // The airframe is modelled as a sphere of DRONE_DENSITY for drag.
        float radius = (float) Math.cbrt(
                3f * DRONE_MASS / (DRONE_DENSITY * 4f * (float) Math.PI)
        );
        float area = (float) Math.PI * radius * radius;
        dragFactor = AIR_DENSITY * area * DRAG_COEFFICIENT / 2f;

        reset(0f);
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Puts the motors at ambient temperature and spins them up to the speed
     * they would settle at for this throttle with the drone at rest.
     * Call when taking off so the drone doesn't start with dead motors.
     */
    public void reset(float throttle) {
        for (int i = 0; i < 4; i++) {
            internalEnergy[i] = 0f;
            temp[i] = REF_TEMP;
            motorVel[i] = 0f;
            motorPos[i] = 0f;
        }

        velocity.zero();
        droneUp.set(0, 1, 0);
        droneLeft.set(1, 0, 0);

        float vbat = getBatteryVoltage(clamp01(throttle));
        for (float t = 0f; t < 0.25f; t += MAX_TIME_STEP) {
            for (int motor = 0; motor < 4; motor++) {
                float useVbat = (motor % 2 == 0 ? -1f : 1f) * vbat;
                motorVel[motor] = advanceMotorVel(
                        motor, motorVel[motor], MAX_TIME_STEP, useVbat,
                        temp[motor]
                );
            }
        }
    }

    /**
     * Advances the simulation by dt seconds.
     *
     * @param throttle    0..1
     * @param orientation drone attitude; local +Y is "up" (thrust direction)
     *                    and local -Z is forward
     * @param vel         world velocity in m/s. Read, and overwritten with
     *                    the new velocity. Collisions are NOT applied here.
     */
    public void step(
            float dt,
            float throttle,
            Quaternionf orientation,
            Vector3f vel
    ) {
        long tic = System.nanoTime();

        orientation.transform(new Vector3f(0f, 1f, 0f), droneUp);
        Vector3f droneLook = orientation.transform(
                new Vector3f(0f, 0f, -1f), new Vector3f()
        );
        droneUp.cross(droneLook, droneLeft);

        velocity.set(vel);

        float vbat = getBatteryVoltage(clamp01(throttle));
        Vector3f gravityForce = new Vector3f(0f, -GRAVITY * DRONE_MASS, 0f);

        int maxIterations = (int) (dt / MAX_TIME_STEP) + 10;
        float timeProcessed = 0f;
        for (int i = 0; i < maxIterations && (dt - timeProcessed) > 0.00001f;
             i++) {
            float timeStep = Math.min(MAX_TIME_STEP, dt - timeProcessed);

            // Quadratic drag: speed^2 * dragFactor, opposite to velocity.
            float speed = velocity.length();
            Vector3f netForce = normalizedOrZero(velocity)
                    .mul(-speed * speed * dragFactor)
                    .add(gravityForce);

            // netForce must be calculated before motorVel is mutated.
            for (int motor = 0; motor < 4; motor++) {
                Vector3f[] results = getTotalForcesOnProp(
                        motor, motorVel[motor]
                );
                netForce.add(results[0]);

                float useVbat = (motor % 2 == 0 ? -1f : 1f) * vbat;

                float nextMotorVel = advanceMotorVel(
                        motor, motorVel[motor], timeStep, useVbat, temp[motor]
                );
                internalEnergy[motor] = getNextInternalEnergy(
                        motor, internalEnergy[motor], temp[motor], useVbat,
                        motorVel[motor], timeStep
                );
                temp[motor] = getNextTemp(internalEnergy[motor]);

                // Keep the blades turning so lift and drag average out over
                // a revolution when the drone is moving through the air.
                motorPos[motor] = wrapAngle(
                        motorPos[motor] + motorVel[motor] * timeStep
                );
                motorVel[motor] = nextMotorVel;
            }

            velocity.fma(timeStep / DRONE_MASS, netForce);

            timeProcessed += timeStep;

            if (System.nanoTime() - tic > MAX_STEP_BUDGET_NANOS) {
                // Running behind. Drop the remaining time rather than
                // spiralling.
                break;
            }
        }

        vel.set(velocity);
    }

    public float[] getMotorVel() {
        return motorVel;
    }

    public float[] getMotorPos() {
        return motorPos;
    }

    public float[] getMotorTemp() {
        return temp;
    }

    public boolean isOverheat() {
        float averageRes = 0f;
        for (int i = 0; i < 4; i++) {
            averageRes += getHotResistance(temp[i]);
        }
        averageRes /= 4f;
        return (refResistance / averageRes) < 0.5f;
    }

    // ---------------------------------------------------------------------
    // Hover throttle search
    // ---------------------------------------------------------------------

    private static float findHoverThrottle() {
        AdvancedPhysicsCore core = new AdvancedPhysicsCore();
        float weight = DRONE_MASS * GRAVITY;

        float lo = 0f;
        float hi = 1f;
        for (int i = 0; i < 16; i++) {
            float mid = (lo + hi) * 0.5f;
            core.reset(mid);
            if (core.totalThrust() < weight) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) * 0.5f;
    }

    /** Vertical thrust (N) of all four props at the current motor speeds. */
    private float totalThrust() {
        float thrust = 0f;
        for (int motor = 0; motor < 4; motor++) {
            Vector3f force = getTotalForcesOnProp(motor, motorVel[motor])[0];
            thrust += force.dot(droneUp);
        }
        return thrust;
    }

    // ---------------------------------------------------------------------
    // Propeller aerodynamics
    // ---------------------------------------------------------------------

    private static Vector3f getMotorToProp(
            float rotationAngle,
            Vector3f worldDroneUp,
            Vector3f worldDroneRight
    ) {
        return new Quaternionf()
                .rotationAxis(rotationAngle, worldDroneUp.x, worldDroneUp.y,
                        worldDroneUp.z)
                .transform(worldDroneRight, new Vector3f());
    }

    private static Vector3f getWorldPropVelocity(
            Vector3f worldDroneUp,
            Vector3f worldDroneVelocity,
            Vector3f motorToProp,
            float angularSpeed,
            float sectionRadius
    ) {
        Vector3f propVelocity = normalizedOrZero(
                worldDroneUp.cross(motorToProp, new Vector3f())
        );
        return propVelocity
                .mul(angularSpeed * sectionRadius)
                .add(worldDroneVelocity);
    }

    private static Vector3f getFreeVelRelPropPlanar(
            Vector3f worldPropVelocity,
            Vector3f worldVelocityFree,
            Vector3f motorToProp
    ) {
        // Ignore the component of wind blowing down the length of the prop
        // since it contributes nearly 0 drag and lift.
        Vector3f freeVelRelProp = worldVelocityFree.sub(
                worldPropVelocity, new Vector3f()
        );
        Vector3f normalProj = project(freeVelRelProp, motorToProp);
        return freeVelRelProp.sub(normalProj);
    }

    private static float getAoA(
            int motorNumber,
            float propPitch,
            float sectionRadius,
            Vector3f freeVelRelPropPlanar,
            Vector3f worldDroneUp,
            Vector3f motorToProp
    ) {
        float pitchAngle = (float) Math.atan2(
                propPitch,
                2f * (float) Math.PI * sectionRadius
        );

        // The direction wind comes from is opposite its velocity.
        Vector3f windDirection = normalizedOrZero(
                freeVelRelPropPlanar.mul(-1f, new Vector3f())
        );

        Vector3f forwardSpinDirection = worldDroneUp.cross(
                motorToProp, new Vector3f()
        );
        if (motorNumber % 2 == 0) {
            forwardSpinDirection.mul(-1f);
        }
        float sign = Math.signum(worldDroneUp.dot(windDirection));
        if (sign == 0f) {
            sign = 1f;
        }
        float windAngle = sign * angleBetween(forwardSpinDirection,
                windDirection);

        float angleOfAttack = pitchAngle - windAngle;

        if (motorNumber % 2 == 0) {
            angleOfAttack *= -1f;
        }

        return angleOfAttack;
    }

    private static float getPropDragCoefficient(float aoa) {
        // Drag varies like cos of the AoA between a minimum (blade edge-on
        // to the wind) and a maximum (blade flat to the wind). A 1.15 root
        // fits real data better than a plain cosine.
        float min = 0.4f;
        float max = 1.8f;
        float minWrapped = (float) Math.pow(min, 1.15);
        float maxWrapped = (float) Math.pow(max, 1.15);
        float generalShape = -(float) Math.cos(aoa * 2f) + 1f;
        float scaledAndOffset =
                generalShape / 2f * (maxWrapped - minWrapped) + minWrapped;
        return (float) Math.pow(scaledAndOffset, 1.0 / 1.15);
    }

    private static float getPropLiftCoefficient(float aoa) {
        // A "tilted" sine wave: flat plate lift plus a NACA-like bump.

        // Bound aoa to mod PI.
        if (aoa < 0) {
            aoa = (float) Math.PI + (aoa % (float) Math.PI);
        } else {
            aoa = aoa % (float) Math.PI;
        }

        float pi = (float) Math.PI;
        float alp = 15f / 2f * (pi / 180f);
        float sqrt2OverPi = (float) Math.sqrt(2f / pi);
        float naca = sqrt2OverPi * aoa * aoa
                * (float) Math.exp(-(aoa * aoa) / (2f * alp * alp))
                / (alp * alp * alp);
        naca = naca / 7f;

        float antiaoa = pi - aoa;
        float antinaca = -sqrt2OverPi * antiaoa * antiaoa
                * (float) Math.exp(-(antiaoa * antiaoa) / (2f * alp * alp))
                / (alp * alp * alp);
        antinaca = antinaca / 7f;

        float plate = (float) Math.sin(aoa * 2f);

        return plate + naca + antinaca;
    }

    private static Vector3f getPropDragForce(
            Vector3f freeVelRelPropPlanar,
            float dragCoefficient,
            float aoa,
            float sectionLength,
            float sectionWidth
    ) {
        float flowSpeed = freeVelRelPropPlanar.length();

        // The prop is a flat plate with a fixed thickness. Only the radius
        // is a user variable.
        float thickness = 0.002f;
        float referenceArea = Math.abs(sectionLength * sectionWidth
                * (float) Math.sin(aoa))
                + Math.abs(sectionLength * thickness * (float) Math.cos(aoa));

        float forceMag = dragCoefficient * AIR_DENSITY * flowSpeed * flowSpeed
                * referenceArea / 2f;

        // Drag is always along the airflow.
        return normalizedOrZero(freeVelRelPropPlanar).mul(forceMag);
    }

    private static Vector3f getPropLiftForce(
            Vector3f freeVelRelPropPlanar,
            Vector3f motorToProp,
            float liftCoefficient,
            float sectionLength,
            float sectionWidth
    ) {
        float flowSpeed = freeVelRelPropPlanar.length();
        float referenceArea = sectionLength * sectionWidth;

        float forceMag = liftCoefficient * AIR_DENSITY * flowSpeed * flowSpeed
                * referenceArea / 2f;

        // Lift is perpendicular to the airflow.
        Vector3f windDirection = normalizedOrZero(
                freeVelRelPropPlanar.mul(-1f, new Vector3f())
        );
        return normalizedOrZero(motorToProp)
                .cross(windDirection)
                .mul(forceMag);
    }

    /**
     * Returns {net force on the prop (N), net drag torque (N m)} in world
     * space, for one motor at the given rotational velocity.
     */
    private Vector3f[] getTotalForcesOnProp(int motorNumber, float rotvel) {
        Vector3f netForce = new Vector3f();
        Vector3f netDragTorque = new Vector3f();

        // Free-stream wind. Could be made non-zero to add wind.
        Vector3f worldVelocityFree = new Vector3f();

        Vector3f droneRight = droneLeft.mul(-1f, new Vector3f());

        // Rotation angle 0 is droneRight.
        float initRotationAngle = motorPos[motorNumber];
        float sectionLength = PROP_RADIUS / PROP_SECTIONS;

        for (int i = 0; i < BLADES; i++) {
            float rotationAngle = initRotationAngle
                    + (float) (2.0 * Math.PI) * i / BLADES;
            Vector3f motorToProp = getMotorToProp(
                    rotationAngle, droneUp, droneRight
            );

            // Each radial section has its own airflow and angle of attack.
            for (int j = 0; j < PROP_SECTIONS; j++) {
                // Measure from the middle of the section.
                float sectionRadius = (j + 0.5f) / PROP_SECTIONS * PROP_RADIUS;

                if (sectionRadius < MOTOR_WIDTH * 0.5f) {
                    // The part of the prop over the motor makes no lift or
                    // drag.
                    continue;
                }

                // Blade chord tapers to 0 toward the hub.
                float sectionWidth = PROP_WIDTH;
                if (sectionRadius < MOTOR_WIDTH) {
                    sectionWidth *= sectionRadius / MOTOR_WIDTH;
                }

                Vector3f worldPropVelocity = getWorldPropVelocity(
                        droneUp, velocity, motorToProp, rotvel, sectionRadius
                );
                Vector3f freeVelRelPropPlanar = getFreeVelRelPropPlanar(
                        worldPropVelocity, worldVelocityFree, motorToProp
                );
                float aoa = getAoA(
                        motorNumber, PROP_PITCH, sectionRadius,
                        freeVelRelPropPlanar, droneUp, motorToProp
                );
                float dragCoefficient = getPropDragCoefficient(aoa);
                float liftCoefficient = getPropLiftCoefficient(aoa);
                Vector3f dragForce = getPropDragForce(
                        freeVelRelPropPlanar, dragCoefficient, aoa,
                        sectionLength, sectionWidth
                );
                Vector3f liftForce = getPropLiftForce(
                        freeVelRelPropPlanar, motorToProp, liftCoefficient,
                        sectionLength, sectionWidth
                );

                netDragTorque.add(
                        motorToProp.cross(dragForce, new Vector3f())
                                .mul(sectionRadius)
                );
                netForce.add(dragForce);
                netForce.add(liftForce);
            }
        }

        return new Vector3f[]{netForce, netDragTorque};
    }

    // ---------------------------------------------------------------------
    // Motor electrical model
    // ---------------------------------------------------------------------

    private static float dflux_dt(
            float x,
            float slotW,
            float R,
            float h,
            float m,
            float rotvel
    ) {
        float gap = 0.00159f;

        float xlow = -slotW / 2f;
        float xhigh = slotW / 2f;

        float gmin = gap * gap + (x + xlow) * (x + xlow);
        float gmax = gap * gap + (x + xhigh) * (x + xhigh);

        float c1 = VAC * h * R * rotvel * m / (4f * (float) Math.PI);
        float c2 = 3f * gap * gap;

        return (float) (c1 * (c2 * (Math.pow(gmax, -5.0 / 2.0)
                - Math.pow(gmin, -5.0 / 2.0))
                - (Math.pow(gmax, -3.0 / 2.0) - Math.pow(gmin, -3.0 / 2.0))));
    }

    private static int getPoles(float motorWidth) {
        return motorWidth >= 0.018f ? 14 : 12;
    }

    private static int getSlots(float motorWidth) {
        return motorWidth >= 0.018f ? 12 : 9;
    }

    private static float getSlotW(float R, float slots) {
        return 2f * (float) Math.PI * R / slots - 0.0005f;
    }

    private static float getMagW(float R, float poles) {
        return 2f * (float) Math.PI * R / poles - 0.0005f;
    }

    private static float getPoleMagneticMoment(float R, float h, float poles) {
        float magW = getMagW(R, poles);
        float magD = 0.001f;
        return 1.48f / VAC * magW * h * magD;
    }

    /**
     * The number of loops of a single phase. A single phase may span several
     * slots, but that is accounted for by kv.
     */
    private static float getN(
            float R,
            float h,
            float kv,
            float poles,
            float slots
    ) {
        float slotW = getSlotW(R, slots);
        float magW = getMagW(R, poles);
        float m = getPoleMagneticMoment(R, h, poles);

        float x1 = -magW + magW / 2f;
        float x2 = magW / 2f;
        float x3 = magW + magW / 2f;

        float d1 = dflux_dt(x1, slotW, R, h, m, 1f);
        float d2 = dflux_dt(x2, slotW, R, h, -m, 1f);
        float d3 = dflux_dt(x3, slotW, R, h, m, 1f);
        float d = d1 + d2 + d3;

        return Math.round(1f / (d * kv));
    }

    private static float computeRefResistance(
            float R,
            float h,
            float kv,
            float poles,
            float slots
    ) {
        float slotW = getSlotW(R, slots);
        float N = getN(R, h, kv, poles, slots);

        float loopPeri = 2f * h + 2f * slotW;
        float wireLengthInches = N * loopPeri * INCHES_PER_METER;
        return wireLengthInches / 12f / 1000f * RESISTANCE_PER_1000_FT;
    }

    private static float computeMotorInertia() {
        float bladeLength = PROP_RADIUS;
        float propInertia = BLADES / 12f * BLADE_MASS
                * (4f * bladeLength * bladeLength + PROP_WIDTH * PROP_WIDTH);
        float bellInertia = BELL_MASS * (MOTOR_WIDTH * 0.5f)
                * (MOTOR_WIDTH * 0.5f);
        return propInertia + bellInertia;
    }

    private float getHotResistance(float temp) {
        return refResistance * (1f + TEMP_COEFF * (temp - REF_TEMP));
    }

    private float getCurrent(float resistance, float vbat, float rotvel) {
        float backEMF = -rotvel / kv;
        return (vbat + backEMF) / resistance;
    }

    private float getTorque(float current) {
        return current / kv;
    }

    private float getRotAccel(
            int motorNumber,
            float rotvel,
            float vbat,
            float temp
    ) {
        float resistance = getHotResistance(temp);
        float current = getCurrent(resistance, vbat, rotvel);
        float torque = getTorque(current);
        float dragTorque = getTotalForcesOnProp(motorNumber, rotvel)[1]
                .dot(droneUp);
        return (torque + dragTorque) / inertia;
    }

    private float advanceMotorVel(
            int motorNumber,
            float rotvel,
            float timeStep,
            float vbat,
            float temp
    ) {
        // Classic RK4.
        float k1 = getRotAccel(motorNumber, rotvel, vbat, temp);
        float k2 = getRotAccel(
                motorNumber, rotvel + (timeStep / 2f) * k1, vbat, temp
        );
        float k3 = getRotAccel(
                motorNumber, rotvel + (timeStep / 2f) * k2, vbat, temp
        );
        float k4 = getRotAccel(
                motorNumber, rotvel + timeStep * k3, vbat, temp
        );
        float nextVel = rotvel + timeStep * (k1 + 2f * k2 + 2f * k3 + k4) / 6f;

        if (Float.isInfinite(nextVel) || Float.isNaN(nextVel)) {
            nextVel = 0f;
        }

        float maxSpeed = kv * BATTERY_CELLS * 4.2f;
        if (Math.abs(nextVel) > maxSpeed) {
            nextVel = maxSpeed * Math.signum(nextVel);
        }

        return nextVel;
    }

    private float getBatteryVoltage(float throt) {
        // Voltage sags a little under load.
        float sag = 4.0f + Math.abs(throt) * (3.6f - 4.0f);
        return sag * BATTERY_CELLS * throt;
    }

    // ---------------------------------------------------------------------
    // Motor thermal model
    // ---------------------------------------------------------------------

    private float getInternalEnergyLost(float temp, float timeStep) {
        float timeConstant = heatCap / (HEAT_TRANSFER * motorSurfaceArea);
        float cooledTemp = REF_TEMP
                + (temp - REF_TEMP) * (float) Math.exp(-timeStep / timeConstant);
        return heatCap * (cooledTemp - temp);
    }

    private float getInternalEnergyGained(
            float vbat,
            float rotvel,
            float timeStep
    ) {
        float current = getCurrent(refResistance, vbat, rotvel);
        float heatingPower = current * current * refResistance;
        return heatingPower * timeStep;
    }

    private float getNextInternalEnergy(
            int motorNumber,
            float internalEnergy,
            float tempIn,
            float vbat,
            float rotvel,
            float timeStep
    ) {
        float nextEnergy = internalEnergy
                + getInternalEnergyLost(tempIn, timeStep)
                + getInternalEnergyGained(vbat, rotvel, timeStep);

        if (Float.isInfinite(nextEnergy) || Float.isNaN(nextEnergy)) {
            nextEnergy = 0f;
            temp[motorNumber] = 0f;
        }

        return nextEnergy;
    }

    private float getNextTemp(float nextInternalEnergy) {
        return nextInternalEnergy / heatCap + REF_TEMP;
    }

    // ---------------------------------------------------------------------
    // Small vector helpers. JOML's normalize() gives NaN for a zero vector,
    // while the original jME code left it as zero, so these keep that.
    // ---------------------------------------------------------------------

    private static Vector3f normalizedOrZero(Vector3f v) {
        float lengthSquared = v.lengthSquared();
        Vector3f result = new Vector3f(v);
        if (lengthSquared == 0f) {
            return result.zero();
        }
        return result.mul(1f / (float) Math.sqrt(lengthSquared));
    }

    /** The part of a that lies along b. */
    private static Vector3f project(Vector3f a, Vector3f b) {
        float bLengthSquared = b.lengthSquared();
        if (bLengthSquared == 0f) {
            return new Vector3f();
        }
        return new Vector3f(b).mul(a.dot(b) / bLengthSquared);
    }

    /** Angle between two unit vectors, in radians. */
    private static float angleBetween(Vector3f a, Vector3f b) {
        float dot = Math.max(-1f, Math.min(1f, a.dot(b)));
        return (float) Math.acos(dot);
    }

    private static float wrapAngle(float angle) {
        float twoPi = (float) (2.0 * Math.PI);
        float wrapped = angle % twoPi;
        return wrapped < 0f ? wrapped + twoPi : wrapped;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}