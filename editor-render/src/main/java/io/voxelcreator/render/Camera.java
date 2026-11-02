package io.voxelcreator.render;

import org.joml.Matrix4f;
import org.joml.Vector3d;

/** Double-precision navigation; GPU matrices are relative to the displayed mesh origin. */
public final class Camera {
  private static final float FOV = (float) Math.toRadians(50);
  private static final float NEAR = .05f, FAR = 200000f;

  /** Orthonormal screen basis, independent of absolute world coordinates and mesh origin. */
  private record Axes(Vector3d forward, Vector3d right, Vector3d up) {}

  private Axes axes() {
    double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
    return new Axes(
        new Vector3d(-sy * cp, -sp, -cy * cp),
        new Vector3d(cy, 0, -sy),
        new Vector3d(-sy * sp, cp, -cy * sp));
  }

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
    var basis = axes();
    // Grab-drag: the scene follows the pointer, so the pivot moves left/up in screen axes.
    focus.fma(-dx * speed, basis.right()).fma(dy * speed, basis.up());
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
        .perspective(FOV, aspect, NEAR, FAR)
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

  /**
   * Ray through normalized OpenGL screen coordinates. Start at the near plane, matching rendered
   * clipping; compute direction in double precision without inverting the float far-plane matrix.
   * Mesh origin affects GPU coordinates only and cannot change the absolute world ray.
   */
  public Ray ray(float nx, float ny, float aspect, Vector3d meshOrigin) {
    var basis = axes();
    double tangent = Math.tan(FOV / 2.);
    var offset =
        new Vector3d(basis.forward())
            .fma(nx * (double) aspect * tangent, basis.right())
            .fma(ny * tangent, basis.up());
    return new Ray(eye().fma(NEAR, offset), new Vector3d(offset).normalize());
  }
}
