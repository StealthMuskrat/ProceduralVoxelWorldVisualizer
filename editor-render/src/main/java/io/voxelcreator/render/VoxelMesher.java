package io.voxelcreator.render;

import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * Neighbor-aware greedy faces and corner AO. The sampler provides a one-cell halo, including
 * diagonals.
 */
public final class VoxelMesher {
  private byte[] data = new byte[4096];
  private int count;

  private void vertex(int x, int y, int z, int axis, int sign, int type, int ao) {
    if (count + 16 > data.length) data = Arrays.copyOf(data, data.length * 2);
    shortAt(count, x * 2);
    shortAt(count + 2, y * 2);
    shortAt(count + 4, z * 2);
    data[count + 6] = (byte) (axis == 0 ? sign * 127 : 0);
    data[count + 7] = (byte) (axis == 1 ? sign * 127 : 0);
    data[count + 8] = (byte) (axis == 2 ? sign * 127 : 0);
    data[count + 9] = (byte) (ao * 85);
    data[count + 10] = data[count + 11] = 0;
    for (int i = 0; i < 4; i++) data[count + 12 + i] = (byte) (type >>> (i * 8));
    count += 16;
  }

  private void shortAt(int at, int value) {
    data[at] = (byte) value;
    data[at + 1] = (byte) (value >>> 8);
  }

  public static PackedMesh greedy(int size, MeshBuilder.Cells cells) {
    if (size < 1 || size > 256) throw new IllegalArgumentException("Mesh size 1..256");
    var mesh = new VoxelMesher();
    long[] mask = new long[size * size];
    int[] p = new int[3];
    for (int d = 0; d < 3; d++) {
      int u = (d + 1) % 3, v = (d + 2) % 3;
      for (int slice = 0; slice <= size; slice++) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        for (int j = 0; j < size; j++)
          for (int i = 0; i < size; i++) {
            p[d] = slice;
            p[u] = i;
            p[v] = j;
            int b = cells.get(p[0], p[1], p[2]);
            p[d] = slice - 1;
            int a = cells.get(p[0], p[1], p[2]);
            int type = a > 0 && b == 0 && slice > 0 ? a : b > 0 && a == 0 && slice < size ? -b : 0;
            if (type == 0) {
              mask[j * size + i] = 0;
              continue;
            }
            int ao = 0;
            for (int corner = 0; corner < 4; corner++) {
              int su = corner == 1 || corner == 2 ? 1 : -1, sv = corner >= 2 ? 1 : -1;
              p[d] = type > 0 ? slice : slice - 1;
              p[u] = i + su;
              p[v] = j;
              boolean side1 = cells.get(p[0], p[1], p[2]) > 0;
              p[u] = i;
              p[v] = j + sv;
              boolean side2 = cells.get(p[0], p[1], p[2]) > 0;
              p[u] = i + su;
              boolean diagonal = cells.get(p[0], p[1], p[2]) > 0;
              int value =
                  side1 && side2 ? 0 : 3 - (side1 ? 1 : 0) - (side2 ? 1 : 0) - (diagonal ? 1 : 0);
              ao |= value << (2 * corner);
            }
            mask[j * size + i] = ((long) type << 8) | ao;
          }
        for (int j = 0; j < size; j++)
          for (int i = 0; i < size; ) {
            long key = mask[j * size + i];
            if (key == 0) {
              i++;
              continue;
            }
            int ao = (int) key & 255, w = 1, h = 1;
            boolean uniform =
                (ao & 3) == ((ao >> 2) & 3)
                    && (ao & 3) == ((ao >> 4) & 3)
                    && (ao & 3) == ((ao >> 6) & 3);
            if (uniform) {
              while (i + w < size && mask[j * size + i + w] == key) w++;
              outer:
              while (j + h < size) {
                for (int k = 0; k < w; k++) if (mask[(j + h) * size + i + k] != key) break outer;
                h++;
              }
            }
            int material = (int) (key >> 8), sign = material > 0 ? 1 : -1;
            int[] order =
                ((ao & 3) + ((ao >> 4) & 3) > ((ao >> 2) & 3) + ((ao >> 6) & 3))
                    ? new int[] {0, 1, 3, 1, 2, 3}
                    : new int[] {0, 1, 2, 0, 2, 3};
            if (sign < 0)
              for (int k = 0; k < 6; k += 3) {
                int tmp = order[k + 1];
                order[k + 1] = order[k + 2];
                order[k + 2] = tmp;
              }
            for (int corner : order) {
              p[d] = slice;
              p[u] = i + (corner == 1 || corner == 2 ? w : 0);
              p[v] = j + (corner >= 2 ? h : 0);
              mesh.vertex(p[0], p[1], p[2], d, sign, Math.abs(material), (ao >> (2 * corner)) & 3);
            }
            for (int y = 0; y < h; y++)
              Arrays.fill(mask, (j + y) * size + i, (j + y) * size + i + w, 0);
            i += w;
          }
      }
    }
    return PackedMesh.owned(mesh.data, mesh.count, false);
  }

  public static PackedMesh points(int size, MeshBuilder.Cells cells, int stride) {
    if (size < 1 || size > 256 || stride < 1 || stride > size)
      throw new IllegalArgumentException("Point size 1..256 and stride 1..size required");
    var mesh = new VoxelMesher();
    for (int z = 0; z < size; z += stride) {
      if (Thread.currentThread().isInterrupted()) throw new CancellationException();
      for (int y = 0; y < size; y += stride)
        for (int x = 0; x < size; x += stride) {
          int type = cells.get(x, y, z);
          if (type <= 0) continue;
          mesh.vertex(x, y, z, 1, 1, type, 3);
          mesh.shortAt(mesh.count - 16, 2 * x + Math.min(stride, size - x));
          mesh.shortAt(mesh.count - 14, 2 * y + Math.min(stride, size - y));
          mesh.shortAt(mesh.count - 12, 2 * z + Math.min(stride, size - z));
        }
    }
    return PackedMesh.owned(mesh.data, mesh.count, true);
  }
}
