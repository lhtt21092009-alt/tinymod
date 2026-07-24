package com.example.tinytask;

public class InputRecord {
    public float yaw;
    public float pitch;
    public boolean pressingForward;
    public boolean pressingBack;
    public boolean pressingLeft;
    public boolean pressingRight;
    public boolean jumping;
    public boolean sneaking;
    public boolean attacking;
    public boolean usingItem;

    public InputRecord(float yaw, float pitch, boolean forward, boolean back, boolean left, boolean right, 
                       boolean jumping, boolean sneaking, boolean attacking, boolean usingItem) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.pressingForward = forward;
        this.pressingBack = back;
        this.pressingLeft = left;
        this.pressingRight = right;
        this.jumping = jumping;
        this.sneaking = sneaking;
        this.attacking = attacking;
        this.usingItem = usingItem;
    }
}
