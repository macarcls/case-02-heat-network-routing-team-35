package ru.teplotrassa.api;

import java.util.Map;

/** Identifies the running application separately from engineering rules and saved experience. */
public final class BuildInfo {
  public static final String VERSION = "1.9.5-contest-compliance";
  public static final String ID = "contest-compliance-20260928-r2";
  public static final String UI_PATH = "/tree-1.9.5";

  private BuildInfo() {}

  public static Map<String, String> details() {
    return Map.of("version", VERSION, "buildId", ID, "uiPath", UI_PATH);
  }
}
