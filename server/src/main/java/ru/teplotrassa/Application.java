package ru.teplotrassa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@io.swagger.v3.oas.annotations.OpenAPIDefinition(
    info =
        @io.swagger.v3.oas.annotations.info.Info(
            title = "Теплотрасса — расчётный сервис",
            version = "1.0.0",
            description =
                "Потоковый импорт GeoJSON, диагностика, асинхронный расчёт сети и сметы. Рабочая"
                    + " сессия определяется cookie tt_workspace."))
@SpringBootApplication
public class Application {
  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
