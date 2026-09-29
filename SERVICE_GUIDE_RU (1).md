# Теплотрасса AI 1.9.5 — техническая документация к исходному коду

**Версия приложения:** `1.9.5-contest-compliance`  
**Идентификатор сборки:** `contest-compliance-20260928-r2`  
**Версия расчётных правил:** `LCT-2026-09-28-v1.7-appendix-geometry`  
**Актуально для исходного кода:** 29 сентября 2026 года

Документ описывает устройство Java-сервера, формат данных, алгоритм расчёта, модели машинного обучения, API и порядок внесения изменений. Точки входа в код: `server/src/main/java/ru/teplotrassa/Application.java`, `api/ContestController.java` и `engine/Planner.java`.

## 1. Устройство репозитория

| Путь | Назначение |
| --- | --- |
| `server/pom.xml` | Maven-проект: Spring Boot 2.6.3, Java 11, Spring Web, Data JDBC, Flyway, PostgreSQL, JTS и Proj4j. |
| `server/src/main/java/ru/teplotrassa/data/` | Чтение GeoJSON, объект `Feature`, доступ к данным через `FeatureStore` и `JdbcFeatureStore`, диагностика и подготовка данных. |
| `server/src/main/java/ru/teplotrassa/engine/` | Геометрия, поиск трасс, построение сети, правила, оценка вариантов, профиль глубины и модели машинного обучения. |
| `server/src/main/java/ru/teplotrassa/api/` | REST-контроллеры, очередь заданий, рабочие пространства, файлы расчёта и накопленный опыт. |
| `server/src/main/resources/migrations/` | Миграции схемы PostgreSQL, выполняемые Flyway. |
| `server/src/main/resources/models/` | Встроенные JSON-файлы обученных моделей. |
| `server/src/main/resources/static/tree-1.9.5.*` | HTML, CSS и JavaScript интерфейса, который обращается к серверному API. |
| `server/src/test/java/ru/teplotrassa/` | Проверки API, маршрутизации, геометрии, глубины, обучения и экспорта. |
| `tools/neural/` | Инструменты подготовки и обучения модели ранжирования кандидатов. |
| `Dockerfile`, `docker-compose.yml` | Сборка Java-приложения и запуск с PostgreSQL и постоянными томами. |

Основные классы сгруппированы по ответственности:

- `GeoJsonInput` и `InputDiagnostics` читают и проверяют входные объекты; `JdbcFeatureStore` предоставляет их расчётному ядру.
- `Planner` управляет стратегией поиска и вариантами, `Network` хранит узлы и ветви, `RouteFinder` и `VisibilityRouter` находят допустимые подходы.
- `BuildingAccess` и `SpatialRules` проверяют вход через наружную стену, проходы через ограничения и отступы. `DepthPlanner` рассчитывает продольный профиль.
- `Evaluation`, `ContestScore` и `ConstructionObjectives` рассчитывают стоимость, общую оценку и строительные показатели; `VariantSelector` выбирает разные по назначению сети.
- `GeoJsonOutput` записывает итоговый `FeatureCollection`. `Workspace` ведёт импорт, очередь заданий и файлы, `ExperienceStore` хранит опыт.

## 2. Путь данных через программу

1. `POST /api/v1/datasets` принимает GeoJSON потоком, создаёт запись `datasets`, файл `input.geojson` и SHA-256 исходного файла.
2. `Workspace.importFile()` передаёт объекты из `GeoJsonInput` пакетами в таблицу `features`. `InputDiagnostics` формирует `import-report.json`. Готовый набор получает статус `READY`.
3. `POST /api/v1/datasets/{id}/jobs` создаёт задание `QUEUED` с сериализованными `Planner.Options`. Рабочий поток переводит его в `RUNNING`.
4. `InputData.scenario()` применяет выбранный режим подготовки, затем `Planner.calculate()` строит и проверяет сети. При включённом опыте `Workspace` загружает сохранённые модели и сети через `ExperienceStore`.
5. `GeoJsonOutput.write()` сохраняет `result.geojson`, а `Workspace` — `report.json`. Задание получает статус `DONE` и краткую сводку в базе.
6. Интерфейс получает прогресс через `GET /jobs/{id}`, карту через `GET /jobs/{id}/view`, полные файлы через `/result` и `/report`.

`Workspace` использует пул из `CALCULATION_WORKERS` потоков и очередь на 100 заданий. При отмене `POST /jobs/{id}/cancel` устанавливается токен остановки. После перезапуска сервера начатые импорт и расчёт получают диагностический статус, а сохранённые готовые данные остаются в базе и томе данных.

## 3. Входные данные и внутренняя модель

Вход — непустой GeoJSON `FeatureCollection` в WGS84/CRS84, координаты в порядке «долгота, широта». `GeoJsonInput` обрабатывает объекты по одному, проверяет структуру, типы полей, геометрию и передаёт её в метрическую систему координат для геометрических вычислений. Результат записывается обратно в GeoJSON.

| `properties.object_type` | Геометрия | Роль |
| --- | --- | --- |
| `source` | `Point` | Источник тепла. |
| `heat_network` | `LineString` | Существующий участок сети, в том числе возможное место врезки. |
| `heat_chamber` | `Point` | Существующая камера. |
| `oks_connection_point` | `Point` | Отдельная точка потребителя; `flow_tph` задаёт требуемый расход. |
| `oks_future`, `oks_existing` | Полигон здания | Контур для проверки подхода к вводу. |
| `restriction` | Геометрия ограничения | Зоны и коммуникации, которые определяются через `restriction_type`. |

Входные идентификаторы задаются в `properties.id` строкой или целым числом. Внутри расчёта они приводятся к строке; в экспортируемых ссылках исходные числовые ID восстанавливаются. `InputData.demands()` превращает каждую `oks_connection_point` в отдельный спрос с исходной точкой на конце маршрута.

Режим `dataMode=strict` использует обязательные поля и сообщения диагностики. Режим `dataMode=scenario` позволяет запустить расчёт с оговорёнными в `InputData` допущениями к составу существующей сети; выполненная подготовка перечисляется в `report.json → scenarioChanges`. Некорректная геометрия приводит к диагностической ошибке импорта.

В таблице `features` хранятся свойства, бинарная геометрия и рамка объекта; индексы по типу и пространству используются `JdbcFeatureStore` для выборки ближайших объектов. Схема создаётся миграцией `V1__datasets_and_jobs.sql`. Таблицы `datasets` и `jobs` хранят состояние импорта и заданий.

## 4. Расчёт маршрута

Настройки расчёта задаёт `Planner.Options`. Основные поля:

| Поле | Значение |
| --- | --- |
| `routingStrategy` | `tree` — сеть с общими ветвями; `classic` — классический поиск; `reinforcement` — поиск по эпизодам с политикой действий. |
| `treeSingleRootRequired` | При `tree` выбирается одна общая врезка; значение по умолчанию — `true`. |
| `rankingProfile` | `contest` — конкурсная оценка; `appendix` — расширенная инженерная оценка; `protocol` — альтернативные веса. |
| `mode` | `plan` — план трассы; `depth` — план с профилем глубины. |
| `dataMode` | `strict` или `scenario`. |
| `variantLimit` | Число запрашиваемых вариантов от 1 до 3. |
| `neuralGuidance` | Настройка ранжирования кандидатов для классической стратегии. |
| `treeLearning`, `rlLearning`, `rlRemember` | Обучение в выбранной стратегии и использование сохранённого опыта. |

В стратегии `tree` ядро строит граф допустимых коридоров (`CorridorGraph`, `TrunkGrid`), выбирает точку врезки и последовательно подключает вводы к растущей общей сети. `TreeRoots`, `TrunkLayout`, `GeometricOptimizer` и `JunctionOptimizer` участвуют в выборе ствола, перестройке общих участков и развилок. Ветви проходят точную проверку маршрута и повторную оценку всей сети.

Для ввода внутри здания `BuildingAccess` определяет допустимую наружную стену из ближайших к исходной точке ввода и выбирает место пересечения при построении конечного подхода от выбранной ветви. Исходная точка остаётся конечным узлом. `SpatialRules` контролирует геометрию здания, препятствия, охранные отступы и специальные проходы; `Rules` содержит таблицы DN, пропускной способности, длины, тарифов и коэффициентов. Для наложившихся специальных зон применяется максимальный коэффициент на соответствующем участке.

`DepthPlanner` в режиме `depth` согласует глубины связанных узлов и ветвей, проверяет уклон и вертикальные просветы. Базовая глубина — 3 м, минимальная — 0,7 м, значение по умолчанию для верхней границы — 6 м, предел уклона — 0,10. Профиль использует принятую в коде плоскую поверхность `Z=0`.

## 5. Оценка и выбор вариантов

Конкурсная оценка реализована в `Rules.score()`, `ContestScore` и `Evaluation`:

`S = 0,7 × calculated_cost / 25 000 000 + 0,3 × new_network_length / 100`.

Здесь `construction_cost` — стоимость новых труб, камер и учтённых врезок в существующие камеры; `calculated_cost = construction_cost + unconnected_penalty`. Для каждой неподключённой точки штраф равен `100 000 000 + 500 000 × flow_tph` рублей. `new_network_length` — длина новой сети по горизонтальной проекции. Направление сортировки `score`: меньше — лучше.

`ConstructionObjectives` рассчитывает два дополнительных сравнительных показателя:

- `earthwork_index` учитывает условный объём траншеи, дополнительную глубину и специальные проходы;
- `installation_index` учитывает повороты, изменения и максимальную глубину, длину глубоких участков и узлы сопряжения.

При `routingStrategy=tree` и запросе трёх вариантов `VariantSelector` назначает роли: `balanced` — лучшая общая оценка среди рассчитанных сетей, `earthworks` — проще разработка грунта, `installation` — проще прокладка. Проверяются различимость маршрутов и улучшение соответствующего показателя. Ответ содержит найденные подходящие роли; `search.variantSelection` поясняет выбор. Общая оценка `score` сохраняется для сравнения вариантов между собой.

Поля `connection_complete`, `connected_connection_count`, `required_connection_count` и `unconnected_oks_ids` отражают охват вводов. Если расчёт завершился частичной сетью, `search.status` принимает значение `PARTIAL_SOLUTION`, а стоимость включает штраф. Для полностью подключённых сетей используются `COMPLETE` или `FEWER_VARIANTS_FOUND` в зависимости от числа различающихся маршрутов.

## 6. Модели машинного обучения и обучение

В коде есть два механизма подсказки порядка поиска:

| Код и модель | Применение |
| --- | --- |
| `NeuralRanker` и `models/connection-ranker.json` | Оценивает кандидатов классического поиска по признакам `CandidateFeatures`. Настройка — `neuralGuidance`. |
| `ReinforcementPolicy` и `models/reinforcement-policy.json` | Даёт числовой приоритет возможному действию по признакам `ReinforcementFeatures`. Используется в стратегиях `tree` и `reinforcement`. |

Размер схемы политики `32 → 32 → 1` означает: **32 числовых описания кандидата** поступают на вход, **32 промежуточных элемента** обрабатывают их, **одно число** задаёт приоритет проверки. Геометрию, диаметр, цену и глубину по-прежнему рассчитывают перечисленные выше классы ядра. Применение модели выполняется на Java-сервере.

Локальное обучение дерева устроено так:

1. `TreeTrainingData` собирает действия из одного состояния, для которых уже рассчитано качество продолжения полной сети.
2. Из них формируются пары предпочтений: какое действие привело к лучшей итоговой оценке.
3. `TreePolicyTraining` готовит новые веса на этих парах.
4. `Planner` проводит контрольный поиск с текущими весами и поиск с предложенными. Рабочие веса принимаются, когда новая полная сеть проходит проверки и её оценка сохраняется или улучшается.
5. `ExperienceStore` сохраняет данные и лучшие сети в рабочем пространстве. Следующий совместимый расчёт загружает опыт и повторно проверяет сохранённые сети на актуальных данных.

`PolicyTraining` отвечает за обучение стратегии `reinforcement` в сериях эпизодов. `GeneralTraining` запускается отдельным заданием через `POST /api/v1/experience/train`: сохранённые территории разделяются на обучающие и проверочные; новая общая модель принимается после сравнения на проверочных картах. Минимум для запуска — две обучающие и две проверочные территории. Состояние доступно через `GET /api/v1/experience`.

Для изменения схемы признаков сопоставляйте `ReinforcementFeatures.NAMES` с `feature_names` в JSON модели, а `CandidateFeatures.NAMES` — со схемой `connection-ranker.json`. Проверки формата и размерностей выполняют конструкторы соответствующих моделей.

## 7. API и выходные файлы

Контроллер `ContestController` обслуживает одинаковые маршруты под `/api/v1` и `/api/contest`. Описание OpenAPI доступно по `/v3/api-docs`.

| Метод и путь относительно `/api/v1` | Результат |
| --- | --- |
| `GET /health`, `GET /rules` | Сборка, лимиты и действующие расчётные таблицы. |
| `POST /datasets?name=map.geojson` | Потоковая загрузка GeoJSON; ответ `202` с `id`. |
| `GET /datasets`, `GET /datasets/{id}` | Список наборов, состояние импорта и его сводка. |
| `GET /datasets/{id}/diagnostics` | Диагностика загруженного набора. |
| `GET /datasets/{id}/view?bbox=west,south,east,north` | Геометрия для обзорной карты. |
| `POST /datasets/{id}/jobs` | Фоновый расчёт с JSON-объектом `Planner.Options`; ответ `202` с `id` задания. |
| `GET /jobs`, `GET /jobs/{id}` | История, статус, прогресс и итоговая сводка. |
| `POST /jobs/{id}/cancel` | Отмена задания. |
| `GET /jobs/{id}/view?variant=v1` | Геометрия выбранного варианта для карты. |
| `GET /jobs/{id}/result`, `GET /jobs/{id}/report` | Полные `result.geojson` и `report.json` после завершения. |
| `GET /experience`, `POST /experience/train?passes=1` | Состояние опыта и отдельное задание на обучение общей модели. |

Пример запроса расчёта:

```json
{
  "dataMode": "strict",
  "rankingProfile": "contest",
  "mode": "depth",
  "routingStrategy": "tree",
  "treeSingleRootRequired": true,
  "variantLimit": 3,
  "rlRemember": true,
  "treeLearning": true
}
```

`result.geojson` — один `FeatureCollection` с метаданными и объектами `heat_network`, `heat_chamber`, `technical_node`, `variant_summary`. Поле `variant_id` объединяет объекты одного варианта (`v1`–`v3`). Сводка содержит цену, длину, оценку, число подключений, строительные показатели и сведения о глубине. `report.json` хранит входную диагностику, настройки, проверки, ход поиска, роли вариантов, сведения об обучении и `elapsedMs`. Экспорт ограничен 500 000 000 байт; загрузка — 3 000 000 000 байт.

`tt_workspace` — HTTP cookie рабочего пространства, которое привязывает наборы и задания к клиенту. `ContestController.owner()` создаёт его при первом запросе; собственные учётные записи в приложении кодом не реализованы. При работе с API сохраняйте cookie между загрузкой, запуском расчёта и чтением результата. Для публичного доступа обратный прокси и его авторизация настраиваются отдельно от Java-приложения.

## 8. Сборка и запуск исходного кода

Из корня репозитория:

```bash
mvn -f server/pom.xml test
mvn -f server/pom.xml package
```

Для локального запуска JAR требуются Java 11 и PostgreSQL. Переменные считываются из `server/src/main/resources/application.yml`:

| Переменная | Значение по умолчанию | Где применяется |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/teplotrassa` | Подключение к PostgreSQL. |
| `DATABASE_USER`, `DATABASE_PASSWORD` | `teplotrassa`, `teplotrassa_local` | Учётные данные БД. |
| `DATA_DIR` | `./data` | Входные файлы, результаты, отчёты и опыт. |
| `CALCULATION_WORKERS` | `2` | Число одновременно работающих заданий. |
| `SERVER_PORT` | `8080` | HTTP-порт приложения. |

```bash
java -jar server/target/teplotrassa-server-1.9.5-contest-compliance.jar
curl -fsS http://localhost:8080/api/v1/health
```

Для запуска всей связки из исходников `Dockerfile` собирает JAR в образе Maven/Temurin 11, затем запускает его на Temurin 11 JRE. `docker-compose.yml` поднимает PostgreSQL 16 и сервер, создаёт тома `postgres_data` и `calculation_data`:

```bash
docker compose up -d --build
curl -fsS http://localhost:8081/api/v1/health
```

По умолчанию интерфейс открыт по `http://localhost:8081/tree-1.9.5`. Порт хоста задаётся переменной `PORT`. Для конкретного сервера его можно ограничить адресом loopback в секции `server.ports`; схема доступа через Caddy или Tailscale относится к конфигурации развёртывания, а не к расчётному ядру.

## 9. Проверки при изменении кода

| Изменение | Основные файлы | Подходящие проверки |
| --- | --- | --- |
| Новый тип ограничения или тариф | `Rules.java`, `SpatialRules.java`, `DepthPlanner.java` | `ProtocolTest`, `GeometrySearchTest`, `DepthTest`. |
| Подключение здания и выбор стены | `BuildingAccess.java`, `RouteFinder.java`, `SpatialRules.java` | `BuildingRoutingTest`, `GeometrySearchTest`. |
| Поиск и перестройка общей сети | `Planner.java`, `CorridorGraph.java`, `TrunkLayout.java`, `GeometricOptimizer.java` | `TreeSearchTest`, `TreeGroupsTest`, `FullConnectionTest`. |
| Оценка и роли вариантов | `Evaluation.java`, `ConstructionObjectives.java`, `VariantSelector.java` | `RankingTest`, `RoutingQualityTest`. |
| Признаки и обучение | `CandidateFeatures.java`, `ReinforcementFeatures.java`, `TreePolicyTraining.java`, JSON-модели | `NeuralGuidanceTest`, `TreeLearningTest`, `ExperienceTest`. |
| Контракт API и экспорт | `ContestController.java`, `GeoJsonOutput.java`, `Workspace.java` | `ApiTest`, `StreamingSizeTest`. |
| Таблицы БД | Новая миграция в `server/src/main/resources/migrations/` | Запуск с чистой БД и проверка API. |
| Интерфейс | `tree-1.9.5.html`, `.css`, `.js` | Сверка `BuildInfo`, загрузка набора, запуск и скачивание результата. |

Команда для выбранного набора тестов:

```bash
mvn -f server/pom.xml -Dtest=BuildingRoutingTest,TreeSearchTest,TreeLearningTest test
```

После изменения вычислений проверяйте `GET /api/v1/health`, импорт GeoJSON, статус задания, `report.json → checks` и `result.geojson → variant_summary`. Для изменения схемы БД добавляйте новую миграцию Flyway; версия правил задаётся в `Rules.VERSION`, а версия сборки и путь интерфейса — в `api/BuildInfo.java`.

## 10. Справочник по исходникам

- `docs/API.md` — краткий контракт API и пример настроек.
- `docs/DEPTH.md` — расчёт глубины и вертикальные правила.
- `docs/TREE_LEARNING.md` — пары предпочтений, обновление и проверка весов для дерева.
- `docs/PURPOSE_VARIANTS.md` — показатели и критерии выбора вариантов.
- `docs/APPENDIX_COMPLIANCE_1.9.5.md` — привязка правил расчёта к техническому приложению.
- `VALIDATION.md` — сценарии проверки версии 1.9.5.

