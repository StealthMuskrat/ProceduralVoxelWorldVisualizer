package io.voxelcreator.core;

import io.voxelcreator.model.Diagnostic;
import java.util.UUID;

/**
 * A compilation error with the innermost authored resource/control, retaining its original cause.
 */
public final class CompilationFailure extends IllegalArgumentException {
  private final Diagnostic diagnostic;

  CompilationFailure(UUID resource, UUID item, String parameter, RuntimeException cause) {
    super(cause.getMessage(), cause);
    diagnostic =
        new Diagnostic(
            "INVALID_NOISE",
            Diagnostic.Severity.ERROR,
            cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(),
            "/noise/"
                + resource
                + (item == null ? "" : "/control/" + item)
                + (parameter == null
                    ? ""
                    : parameter.startsWith("inputs/")
                        ? "/inputs/" + parameter.substring(7).replace("~", "~0").replace("/", "~1")
                        : item == null && parameter.startsWith("algorithm/")
                            ? "/" + parameter
                            : "/parameters/" + parameter),
            resource,
            item,
            parameter);
  }

  public Diagnostic diagnostic() {
    return diagnostic;
  }
}
