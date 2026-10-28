package io.voxelcreator.render;

import static org.lwjgl.opengl.GL15.*;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;

/**
 * Context-local optional core/ARB timer queries; timestamp pairs do not alter active elapsed
 * queries.
 */
public final class GlTimestampQueries implements GpuTimerQueries.Backend {
  private boolean core;

  @Override
  public int counterBits() {
    var capabilities = GL.getCapabilities();
    core = capabilities.OpenGL33;
    if (!core && !capabilities.GL_ARB_timer_query) return 0;
    return glGetQueryi(GL33.GL_TIMESTAMP, GL_QUERY_COUNTER_BITS);
  }

  @Override
  public int create() {
    return glGenQueries();
  }

  @Override
  public void timestamp(int query) {
    if (core) GL33.glQueryCounter(query, GL33.GL_TIMESTAMP);
    else ARBTimerQuery.glQueryCounter(query, ARBTimerQuery.GL_TIMESTAMP);
  }

  @Override
  public boolean available(int query) {
    return glGetQueryObjecti(query, GL_QUERY_RESULT_AVAILABLE) != 0;
  }

  @Override
  public long result(int query) {
    return core
        ? GL33.glGetQueryObjectui64(query, GL_QUERY_RESULT)
        : ARBTimerQuery.glGetQueryObjectui64(query, GL_QUERY_RESULT);
  }

  @Override
  public void delete(int query) {
    glDeleteQueries(query);
  }
}
