package io.voxelcreator.render;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Double-precision navigation; GPU matrices are relative to the displayed mesh origin. */
public final class Camera {
  public final Vector3d focus = new Vector3d(0, 16, 0), position = new Vector3d();
  public double yaw = .75, pitch = .55, distance = 100;
  public boolean flying = false;

  public void orbit(double dx, double dy) {
    yaw -= dx * .008;
    pitch = Math.max(-1.5, Math.min(1.5, pitch + dy * .008));
  }

  public void zoom(double wheel) {
    distance = Math.max(.25, Math.min(100000, distance * Math.exp(-wheel * .12)));
  }

  public void pan(double dx, double dy) {
    double speed = distance * .0015;
    focus.x += (-Math.cos(yaw) * dx - Math.sin(yaw) * dy) * speed;
    focus.z += (Math.sin(yaw) * dx - Math.cos(yaw) * dy) * speed;
  }

  public Vector3d eye() {
    if (flying) return new Vector3d(position);
    return new Vector3d(focus)
        .add(
            Math.sin(yaw) * Math.cos(pitch) * distance,
            Math.sin(pitch) * distance,
            Math.cos(yaw) * Math.cos(pitch) * distance);
  }

  public void enterFly() {
    position.set(eye());
    flying = true;
  }

  public void leaveFly() {
    focus
        .set(position)
        .add(
            -Math.sin(yaw) * Math.cos(pitch) * distance,
            -Math.sin(pitch) * distance,
            -Math.cos(yaw) * Math.cos(pitch) * distance);
    flying = false;
  }

  public void move(double forward, double strafe, double vertical, double dt) {
    double speed = 30 * dt;
    position.add(
        (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) * speed,
        vertical * speed,
        (-Math.cos(yaw) * forward - Math.sin(yaw) * strafe) * speed);
  }

  public void teleport(double x, double y, double z) {
    if (flying) position.set(x, y, z);
    else focus.set(x, y, z);
  }

  public Matrix4f matrix(float aspect, Vector3d origin) {
    Vector3d eye = eye().sub(origin),
        target =
            flying
                ? new Vector3d(eye)
                    .add(
                        -Math.sin(yaw) * Math.cos(pitch),
                        -Math.sin(pitch),
                        -Math.cos(yaw) * Math.cos(pitch))
                : new Vector3d(focus).sub(origin);
    return new Matrix4f()
        .perspective((float) Math.toRadians(50), aspect, .05f, 200000f)
        .lookAt(
            (float) eye.x,
            (float) eye.y,
            (float) eye.z,
            (float) target.x,
            (float) target.y,
            (float) target.z,
            0,
            1,
            0);
  }

  public record Ray(Vector3d origin, Vector3d direction) {}

  public Ray ray(float nx, float ny, float aspect, Vector3d meshOrigin) {
    Matrix4f inv = matrix(aspect, meshOrigin).invert();
    Vector3f near = new Vector3f(nx, ny, -1).mulProject(inv),
        far = new Vector3f(nx, ny, 1).mulProject(inv);
    return new Ray(new Vector3d(near).add(meshOrigin), new Vector3d(far.sub(near)).normalize());
  }
}
