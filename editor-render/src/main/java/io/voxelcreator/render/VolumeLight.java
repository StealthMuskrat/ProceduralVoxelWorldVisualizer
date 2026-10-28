package io.voxelcreator.render;

/** Display-only directional attenuation through the accepted scalar texture. */
public final class VolumeLight {
  private VolumeLight() {}

  public static final double LX = .4 / Math.sqrt(1.25),
      LY = 1 / Math.sqrt(1.25),
      LZ = .3 / Math.sqrt(1.25);

  public record Settings(boolean enabled, int steps) {
    public Settings {
      if (!validSteps(steps))
        throw new IllegalArgumentException("Volume light steps must be 8, 16 or 32");
    }
  }

  public static boolean validSteps(int steps) {
    return steps == 8 || steps == 16 || steps == 32;
  }

  public static double transmittance(
      VolumeData volume, VolumeTransfer transfer, double x, double y, double z, Settings settings) {
    if (!settings.enabled) return 1;
    if (!Double.isFinite(x)
        || !Double.isFinite(y)
        || !Double.isFinite(z)
        || x < 0
        || y < 0
        || z < 0
        || x > volume.size()
        || y > volume.size()
        || z > volume.size()) throw new IllegalArgumentException("Light sample outside volume");
    double length =
        Math.min(
            (volume.size() - x) / LX, Math.min((volume.size() - y) / LY, (volume.size() - z) / LZ));
    double step = length / settings.steps, optical = 0;
    for (int i = 0; i < settings.steps; i++) {
      double d = (i + .5) * step;
      float raw = (float) volume.interpolateEncoded(x + LX * d, y + LY * d, z + LZ * d);
      if (raw >= transfer.threshold()) optical += transfer.mapped(raw) * transfer.opacity() * step;
    }
    return Math.exp(-optical);
  }

  static String fragment() {
    return """
uniform int volumeLightEnabled,volumeLightSteps;
float volumeIllumination(vec3 p){
  if(volumeLightEnabled==0||opacity==0.0)return 1.0;
  vec3 light=normalize(vec3(.4,1,.3));
  vec3 remain=max(vec3(0),vec3(extent)-p)/light;
  float distance=min(remain.x,min(remain.y,remain.z));
  if(distance<=0.0)return 1.0;
  float ds=distance/float(volumeLightSteps),optical=0.0;
  for(int j=0;j<32;j++){
    if(j>=volumeLightSteps)break;
    float raw=texture(density,(p+light*((float(j)+.5)*ds))/extent).r;
    if(raw>=threshold)optical+=clamp(raw*displayTransfer.x+displayTransfer.y,0.0,1.0)*opacity*ds;
  }
  return .35+.65*exp(-optical);
}
""";
  }
}
