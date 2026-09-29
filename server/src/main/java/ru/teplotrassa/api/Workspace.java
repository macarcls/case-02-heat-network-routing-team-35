package ru.teplotrassa.api;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

@Component
public class Workspace {
  public final JdbcTemplate db;
  public final ObjectMapper json;
  public final Path root;
  public final ExperienceStore experience;
  private final ThreadPoolExecutor pool;
  private final JobRepository jobs;
  private final Map<UUID, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

  public Workspace(
      JdbcTemplate db,
      ObjectMapper json,
      JobRepository jobs,
      @Value("${teplotrassa.storage}") String directory,
      @Value("${teplotrassa.workers}") int workers,
      @Value("${teplotrassa.queue-capacity}") int queue)
      throws IOException {
    this.db = db;
    this.json = json;
    this.jobs = jobs;
    root = Paths.get(directory).toAbsolutePath().normalize();
    Files.createDirectories(root);
    experience = new ExperienceStore(root, json);
    pool =
        new ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queue),
            new ThreadPoolExecutor.AbortPolicy());
  }

  @PostConstruct
  public void recover() {
    db.update(
        "UPDATE jobs SET status='FAILED', message='Сервер перезапущен; исходные данные"
            + " сохранены.',updated_at=now() WHERE status IN ('RUNNING','QUEUED')");
    db.update(
        "UPDATE datasets SET status='FAILED',error='Импорт прерван перезапуском.' WHERE"
            + " status IN ('UPLOADING','IMPORTING')");
  }

  @PreDestroy
  public void stop() {
    cancellations.values().forEach(v -> v.set(true));
    pool.shutdownNow();
  }

  public Path directory(UUID id) throws IOException {
    Path dir = root.resolve(id.toString());
    Files.createDirectories(dir);
    return dir;
  }

  public void importFile(UUID dataset, Path path) {
    submit(
        () -> {
          db.update("UPDATE datasets SET status='IMPORTING' WHERE id=?", dataset);
          try {
            List<Feature> batch = new ArrayList<>();
            AtomicLong total = new AtomicLong(),
                points = new AtomicLong(),
                batchBytes = new AtomicLong();
            new GeoJsonInput(json)
                .read(
                    path,
                    f -> {
                      if (f.type.equals("oks_connection_point")) points.incrementAndGet();
                      total.incrementAndGet();
                      batch.add(f);
                      batchBytes.addAndGet(
                          f.geometry.getNumPoints() * 48L + f.properties.toString().length() * 4L);
                      if (batch.size() == 100 || batchBytes.get() >= 16L * 1024 * 1024) {
                        JdbcFeatureStore.insert(db, dataset, batch);
                        batch.clear();
                        batchBytes.set(0);
                        db.update(
                            "UPDATE datasets SET" + " feature_count=?,oks_count=? WHERE" + " id=?",
                            total.get(),
                            points.get(),
                            dataset);
                      }
                    },
                    false);
            if (!batch.isEmpty()) JdbcFeatureStore.insert(db, dataset, batch);
            FeatureStore store = new JdbcFeatureStore(db, dataset, json);
            List<Map<String, Object>> findings = InputDiagnostics.inspect(store);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("diagnostics", findings);
            report.put(
                "strictReady", findings.stream().noneMatch(f -> f.get("severity").equals("error")));
            report.put("connectionPointCount", points.get());
            report.put("featureCount", total.get());
            report.put("coordinatesChanged", false);
            json.writeValue(directory(dataset).resolve("import-report.json").toFile(), report);
            db.update(
                "UPDATE datasets SET status='READY',feature_count=?,oks_count=?" + " WHERE id=?",
                total.get(),
                points.get(),
                dataset);
          } catch (Exception e) {
            db.update("DELETE FROM features WHERE dataset_id=?", dataset);
            db.update(
                "UPDATE datasets SET status='FAILED',error=? WHERE id=?", message(e), dataset);
          }
        },
        () ->
            db.update(
                "UPDATE datasets SET status='FAILED',error='Очередь заполнена.'" + " WHERE id=?",
                dataset));
  }

  public JsonNode importReport(UUID dataset) throws IOException {
    return json.readTree(directory(dataset).resolve("import-report.json").toFile());
  }

  public JdbcFeatureStore validatedStore(UUID dataset) throws IOException {
    JdbcFeatureStore store = new JdbcFeatureStore(db, dataset, json);
    store.setDiagnostics(
        json.convertValue(
            importReport(dataset).path("diagnostics"),
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
    return store;
  }

  public void calculate(UUID job, UUID dataset, Planner.Options options) {
    AtomicBoolean token = new AtomicBoolean();
    cancellations.put(job, token);
    submit(
        () -> {
          if (token.get()) {
            finishCancelled(job);
            return;
          }
          db.update("UPDATE jobs SET status='RUNNING',updated_at=now() WHERE id=?", job);
          try {
            FeatureStore store = InputData.scenario(validatedStore(dataset), options);
            AtomicInteger progress = new AtomicInteger(-1);
            Planner planner =
                new Planner(
                    store,
                    options,
                    token::get,
                    (percent, text) -> {
                      if (progress.getAndSet(percent) != percent
                          || text.startsWith("Эпизод ")
                          || text.startsWith("Дерево:")
                          || text.startsWith("Перестройка дерева:")
                          || text.startsWith("Дообучение"))
                        db.update(
                            "UPDATE jobs SET"
                                + " progress=?,message=?,updated_at=now()"
                                + " WHERE id=?",
                            percent,
                            text,
                            job);
                    });
            Planner.Result result;
            if (options.rlRemember) {
              String owner =
                  db.queryForObject("SELECT owner FROM datasets WHERE id=?", String.class, dataset);
              String sha =
                  db.queryForObject(
                      "SELECT sha256 FROM datasets WHERE id=?", String.class, dataset);
              db.update(
                  "UPDATE jobs SET message='Ожидание доступа к сохранённому"
                      + " опыту',updated_at=now() WHERE id=?",
                  job);
              try (ExperienceStore.Lease lease = experience.lock(owner, token::get)) {
                ExperienceStore.Session session = experience.open(owner, sha, dataset, options);
                planner.withExperience(session.networks, session::save);
                if (options.routingStrategy.equals("tree"))
                  planner.withTreeLearning(
                      session.treeTraining, options.treeLearning ? session::saveTree : null);
                else if (options.routingStrategy.equals("reinforcement")) {
                  if (options.rlLearning) planner.withLearning(session.training);
                  else planner.withReinforcementPolicy(session.training.policy());
                }
                result = planner.calculate();
                result.search.put(
                    "experience",
                    Map.of(
                        "enabled",
                        true,
                        "resumedTraining",
                        options.routingStrategy.equals("tree")
                            ? session.treeResumed
                            : session.resumed,
                        "territoryKey",
                        experience.key(sha, options),
                        "notes",
                        session.notes,
                        "bestNetworksRevalidated",
                        true));
              }
            } else {
              result = planner.calculate();
              result.search.put("experience", Map.of("enabled", false, "resumedTraining", false));
            }
            if (token.get()) {
              finishCancelled(job);
              return;
            }
            result.metadata.put("application", BuildInfo.details());
            result.metadata.put("rules_version", Rules.VERSION);
            result.metadata.put("data_mode", options.dataMode);
            result.metadata.put("ranking_profile", options.rankingProfile);
            result.metadata.put("cost_weight", options.rankingProfile.equals("protocol") ? .3 : .7);
            result.metadata.put(
                "length_weight", options.rankingProfile.equals("protocol") ? .7 : .3);
            result.metadata.put("existing_load_percent", options.existingLoadPercent);
            result.metadata.put(
                "input_complete", importReport(dataset).path("strictReady").asBoolean());
            Path output = directory(job).resolve("result.geojson");
            GeoJsonOutput.write(output, result, store, json);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("application", BuildInfo.details());
            report.put("input", importReport(dataset));
            report.put(
                "inputSha256",
                db.queryForObject("SELECT sha256 FROM datasets WHERE id=?", String.class, dataset));
            report.put("scenarioChanges", InputData.scenarioChanges(store));
            report.put("options", options);
            report.put("rulesVersion", Rules.VERSION);
            report.put("mode", options.mode);
            report.put("ranking", result.metadata.get("ranking"));
            report.put("routingQuality", result.metadata.get("routing_quality"));
            report.put(
                "variants",
                result.variants.stream()
                    .map(v -> v.summary)
                    .collect(java.util.stream.Collectors.toList()));
            report.put(
                "checks",
                result.variants.stream()
                    .map(v -> v.checks)
                    .collect(java.util.stream.Collectors.toList()));
            report.put("diagnostics", result.diagnostics);
            report.put("search", result.search);
            report.put("elapsedMs", result.elapsedMs);
            report.put("outputBytes", Files.size(output));
            report.put(
                "algorithm",
                options.routingStrategy.equals("tree")
                    ? "Обзорный граф всей территории, разрежение до остова"
                        + " коридоров, поиск нескольких частичных деревьев и"
                        + " точная перепрокладка дорогих ветвей."
                        + (options.treeSingleRootRequired
                            ? " Требуется одна общая врезка. На общей"
                                + " карте проходов ветви"
                                + " присоединяются к растущему"
                                + " дереву, сложные конечные подходы"
                                + " проверяются отдельно. Направление"
                                + " каждой проверяемой врезки"
                                + " предварительно проверяется для"
                                + " всех закреплённых входов. После"
                                + " первой врезки новые ветви"
                                + " присоединяются только к этому"
                                + " дереву."
                            : " Допускается несколько врезок;"
                                + " выбирается лучшая полная сеть по"
                                + " score.")
                        + (options.treeGroupRepair
                            ? options.treeSingleRootRequired
                                ? " Общие стволы прокладываются к"
                                    + " узлам, выбранным по"
                                    + " группе вводов, до"
                                    + " подключения потребителей."
                                    + " Перестраиваются общие"
                                    + " участки и целые"
                                    + " поддеревья. Все изменения"
                                    + " проходят точную проверку."
                                : " Совместно перестраиваются" + " группы из 2–4 вводов."
                            : "")
                        + (options.treeSingleRootTrial && !options.treeSingleRootRequired
                            ? " Отдельно сравниваются полные сети с"
                                + " одной общей врезкой; итог"
                                + " выбирается по score, без"
                                + " принудительного объединения."
                            : "")
                        + (options.treeLearning
                            ? " Между полными проходами копия модели"
                                + " обучается на сравнении"
                                + " проверенных полных сетей в"
                                + " одинаковых состояниях. Новые веса"
                                + " принимаются после полного поиска"
                                + " с теми же настройками, если его"
                                + " результат на этом участке не хуже"
                                + " контрольного; глобальная модель"
                                + " не заменяется."
                            : " При резервном поиске сохранённая модель"
                                + " задаёт порядок проверки"
                                + " продолжений без обновления"
                                + " весов.")
                        + " Диффузионная модель не используется. Граф"
                        + " служит подсказкой: все готовые ветви проходят"
                        + " точные геометрические и инженерные проверки."
                        + " Сохраняются только полные сети. Глобальный"
                        + " минимум не гарантируется."
                    : options.routingStrategy.equals("reinforcement")
                        ? "Обучение с подкреплением (REINFORCE): политика"
                            + " выбирает ввод и место присоединения к"
                            + " существующей сети или уже построенной"
                            + " ветви. Несколько эпизодов создают разные"
                            + " сети; выдаются лучшие полные варианты."
                            + " Ветви проходят точные проверки геометрии,"
                            + " расходов, DN и глубины. Повороты"
                            + " штрафуются; после построения выполняется"
                            + " проверяемое упрощение. "
                            + (options.rlLearning
                                ? "Веса обновляются пакетами новых"
                                    + " эпизодов (REINFORCE +"
                                    + " Adam). "
                                : "Обновление весов отключено. ")
                            + (options.rlRemember
                                ? "Опыт и лучшие полные сети"
                                    + " сохраняются между"
                                    + " расчётами. "
                                : "Опыт этого запуска не" + " сохраняется. ")
                            + "Глобальный минимум не гарантируется."
                        : "Многостартовый поиск полного подключения: разные"
                            + " порядки, повторные попытки и локальная"
                            + " перепрокладка ветвей. Сравниваются только"
                            + " полные сети; каждый проверяемый маршрут"
                            + " полностью пересчитывается. Точные прямые"
                            + " и угловые коридоры, A* и удаление лишних"
                            + " изгибов; векторные проверки после выбора"
                            + " диаметра. При включённой нейросетевой"
                            + " подсказке модель задаёт порядок"
                            + " кандидатов. Отсечение основано на"
                            + " аналитической нижней оценке; ограничения"
                            + " проверяет алгоритм. Глобальный минимум не"
                            + " гарантируется.");
            report.put(
                "assumptions",
                List.of(
                    "Каждая заданная точка обязательна к подключению. Штраф за"
                        + " неподключение исключён. Если полная сеть не"
                        + " найдена, готовые варианты не выдаются. Свойства"
                        + " грунта отсутствуют; сложность учитывается по"
                        + " известным спецпроходам и глубине.",
                    "Три назначения: первый вариант имеет лучшую общую оценку"
                        + " среди найденных сетей; второй снижает условный"
                        + " показатель земляных работ; третий снижает показатель"
                        + " сложности прокладки относительно двух первых."
                        + " Разные назначения ищутся отдельно. При отсутствии"
                        + " подходящей отличающейся трассы вариантов будет меньше трёх.",
                    "База оценки: вес стоимости 0,7 и длины 0,3. К длине для"
                        + " ранжирования добавлен штраф сложности: 90° — 25"
                        + " условных метров, 45° — 50, 135° — 75. Учитываются"
                        + " также углы присоединений. Штраф не является метрами"
                        + " трубы или денежной статьёй. Профиль protocol"
                        + " переключает базовые веса.",
                    "Участок одного DN проверяется как связная компонента с"
                        + " суммой длин всех ветвей. Допускается одно повышение"
                        + " номенклатуры.",
                    "При наложении спецпроходов берётся максимальный" + " коэффициент.",
                    "Концы труб совпадают с исходными точками подключения. Для"
                        + " точки внутри здания конечный вход проходит через"
                        + " допустимую наружную стену со стороны сети. Стена"
                        + " выбирается вместе с маршрутом и диаметром, с"
                        + " предпочтением ближайшей к точке присоединения."
                        + " Вход относится только к"
                        + " этой точке: транзит и ответвления внутри здания"
                        + " запрещены. Внутренний отрезок входит в длину, DN,"
                        + " профиль и стоимость по действующему тарифу пары"
                        + " труб; отдельная модель внутренних систем и"
                        + " стоимости проходки стены пока отсутствует.",
                    "Внутренние повороты трасс — 45 или 90 градусов; прямые"
                        + " отрезки могут иметь произвольный азимут. Цена"
                        + " нестандартного отвода не требуется.",
                    options.mode.equals("depth")
                        ? "Профиль глубины непрерывный, без сетки 0,5 м;"
                            + " рельеф Z=0. Глубина до верха труб, Z=-h."
                            + " Врезки и вводы закреплены на 3 м; общий"
                            + " узел имеет единую глубину."
                        : "Выбран первый этап: глубина не проверяется,"
                            + " координаты без Z,"
                            + " depth_start/depth_end=null.",
                    "depthRuleProfile=appendix использует табличные"
                        + " вертикальные просветы; protocol увеличивает их до"
                        + " max(0,7, табличный). Минимальное покрытие 0,7 м"
                        + " сохраняется в обоих профилях.",
                    "Длина и стоимость считаются по горизонтальной проекции."
                        + " Коэффициент глубины применяется только к новой"
                        + " сети.",
                    "Профиль строится на найденном плане, полного поиска в"
                        + " пространстве XYZ нет. Переход, чьи обязательные"
                        + " подходы выходят за ребро графа, отклоняется;"
                        + " автоматического переноса площадки через камеру пока"
                        + " нет."));
            json.writeValue(directory(job).resolve("report.json").toFile(), report);
            db.update(
                "UPDATE jobs SET status='DONE',progress=100,message='Расчёт"
                    + " завершён',summary=?,updated_at=now() WHERE id=?",
                json.writeValueAsString(report),
                job);
          } catch (Exception e) {
            db.update(
                "UPDATE jobs SET status=?,message=?,updated_at=now() WHERE id=?",
                token.get() ? "CANCELLED" : "FAILED",
                message(e),
                job);
          } finally {
            cancellations.remove(job);
          }
        },
        () -> {
          cancellations.remove(job);
          db.update(
              "UPDATE jobs SET status='FAILED',message='Очередь заполнена.' WHERE" + " id=?", job);
        });
  }

  public void trainGeneral(UUID job, String owner, int passes) {
    AtomicBoolean token = new AtomicBoolean();
    cancellations.put(job, token);
    submit(
        () -> {
          try {
            db.update(
                "UPDATE jobs SET status='RUNNING',message='Ожидание доступа к"
                    + " опыту',updated_at=now() WHERE id=?",
                job);
            Map<String, Object> report;
            try (ExperienceStore.Lease lease = experience.lock(owner, token::get)) {
              report =
                  new GeneralTraining(experience, json)
                      .run(
                          owner,
                          passes,
                          (id, options) -> {
                            Integer count =
                                db.queryForObject(
                                    "SELECT count(*) FROM"
                                        + " datasets WHERE id=?"
                                        + " AND owner=? AND"
                                        + " status='READY'",
                                    Integer.class,
                                    id,
                                    owner);
                            if (count == null || count != 1)
                              throw new IOException("Исходный участок опыта" + " недоступен");
                            return InputData.scenario(validatedStore(id), options);
                          },
                          token::get,
                          (percent, text) ->
                              db.update(
                                  "UPDATE jobs SET"
                                      + " progress=?,message=?,updated_at=now()"
                                      + " WHERE id=?",
                                  percent,
                                  text,
                                  job));
            }
            json.writeValue(directory(job).resolve("report.json").toFile(), report);
            db.update(
                "UPDATE jobs SET"
                    + " status=?,progress=100,message=?,summary=?,updated_at=now()"
                    + " WHERE id=?",
                "CANCELLED".equals(report.get("status")) ? "CANCELLED" : "DONE",
                report.get("message"),
                json.writeValueAsString(report),
                job);
          } catch (Exception e) {
            db.update(
                "UPDATE jobs SET status=?,message=?,updated_at=now() WHERE id=?",
                token.get() ? "CANCELLED" : "FAILED",
                message(e),
                job);
          } finally {
            cancellations.remove(job);
          }
        },
        () -> {
          cancellations.remove(job);
          db.update(
              "UPDATE jobs SET status='FAILED',message='Очередь заполнена.' WHERE" + " id=?", job);
        });
  }

  public void cancel(UUID job) {
    JobRecord record =
        jobs.findById(job).orElseThrow(() -> new IllegalArgumentException("Задание не найдено"));
    if (!Set.of("RUNNING", "QUEUED").contains(record.status)) return;
    AtomicBoolean token = cancellations.get(job);
    if (token != null) token.set(true);
    db.update("UPDATE jobs SET cancelled=true WHERE id=?", job);
  }

  private void finishCancelled(UUID job) {
    db.update(
        "UPDATE jobs SET status='CANCELLED',message='Расчёт отменён',updated_at=now() WHERE"
            + " id=?",
        job);
    cancellations.remove(job);
  }

  private void submit(Runnable work, Runnable rejected) {
    try {
      pool.execute(work);
    } catch (RejectedExecutionException e) {
      rejected.run();
    }
  }

  public static String message(Exception e) {
    String text =
        e instanceof org.springframework.dao.DuplicateKeyException
            ? "Идентификаторы объектов должны быть уникальны"
            : e.getMessage();
    if (text == null) text = e.getClass().getSimpleName();
    return text.substring(0, Math.min(text.length(), 2000));
  }
}
