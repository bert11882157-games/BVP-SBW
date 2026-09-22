package com.yourname.berts_vehicle_pack.entity.helicopter;

public final class HelicopterControlInput {
    private short rawInput;

    public void capture(short input) {
        this.rawInput = input;
    }

    public short rawInput() {
        return rawInput;
    }

    public boolean collectiveUp() {
        return pressed(4);
    }

    public boolean collectiveDown() {
        return pressed(8);
    }

    public boolean thrustUp() {
        return pressed(32);
    }

    public boolean thrustDown() {
        return pressed(256);
    }

    public boolean steerLeft() {
        return pressed(1);
    }

    public boolean steerRight() {
        return pressed(2);
    }

    public boolean left() {
        return pressed(1);
    }

    public boolean right() {
        return pressed(2);
    }

    public boolean forward() {
        return pressed(4);
    }

    public boolean back() {
        return pressed(8);
    }

    public boolean up() {
        return pressed(16);
    }

    public boolean down() {
        return pressed(32);
    }

    public boolean ctrl() {
        return pressed(256);
    }

    public boolean fire() {
        return pressed(128);
    }

    private boolean pressed(int mask) {
        return (this.rawInput & mask) != 0;
    }
}

