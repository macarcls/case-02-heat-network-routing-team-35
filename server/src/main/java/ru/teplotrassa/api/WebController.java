package ru.teplotrassa.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebController {
  @GetMapping({
    "/",
    "/contest.html",
    "/tree-1.4.1",
    "/tree-1.5.0",
    "/tree-1.6.0",
    "/tree-1.7.0",
    "/tree-1.8.0",
    "/tree-1.8.0.html",
    "/tree-1.9.0",
    "/tree-1.9.0.html",
    "/tree-1.9.1",
    "/tree-1.9.1.html",
    "/tree-1.9.2",
    "/tree-1.9.3",
    "/tree-1.9.4"
  })
  public String index() {
    return "redirect:" + BuildInfo.UI_PATH;
  }

  @GetMapping(BuildInfo.UI_PATH)
  public String refreshedInterface() {
    return "forward:/tree-1.9.5.html";
  }
}
