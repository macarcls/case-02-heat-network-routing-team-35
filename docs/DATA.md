# Входные и выходные данные

На вход подаётся непустой `FeatureCollection` с геометрией WGS84 (долгота, широта). Числовые ID приводятся к строкам без перенумерации. Все ссылки сохраняют смысл. CRS84 и EPSG:4326 поддержаны; другая система координат отклоняется. Входной файл хранится неизменным вместе с SHA-256.

## Объекты

| object_type | Геометрия | Данные для расчёта |
|---|---|---|
| source | Point | id; один источник |
| heat_network | LineString | diameter, flow_tph, upstream_object_id |
| heat_chamber | Point | diameter, upstream_object_id |
| oks_connection_point | Point | положительный flow_tph; либо oks_id со ссылкой на расход oks_future |
| oks_future | Polygon / MultiPolygon | поддержка полного формата; препятствие с отступом по DN |
| oks_existing | Polygon / MultiPolygon | препятствие с отступом по DN |
| restriction | Polygon / MultiPolygon либо LineString по правилу | restriction_type |

Типы ограничений приложения: `oks`, `park`, `social_area`, `prohibited_site`, `water`, `road`, `tram_tracks`, `gas_pipeline`, `power_cable`. Существующие `heat_network` автоматически учитываются как коммуникации. Для `railway` формализованного правила нет: строгий режим останавливается, исследовательский применяет консервативный запрет. Неизвестное ограничение в исследовательском режиме также становится запрещённым полигоном, исходный тип сохраняется в служебном атрибуте.

Одна точка — один потребитель. Если несколько точек ссылаются на один `oks_id`, а распределение расхода неоднозначно, возвращается ошибка. Нет расчёта расхода из площади, этажности или тепловой нагрузки.

## Строгий и исследовательский режимы

При импорте проверяются формат, уникальность ID и валидность геометрии. Пропуски атрибутов собираются в диагностику; такой файл доступен для просмотра и исследования, но строгий расчёт заблокирован.

Исследовательский режим включается явно в запросе `dataMode: "scenario"`. Недостающие расходы берутся как `capacity(DN) × existingLoadPercent / 100`. Топология восстанавливается только по совпадающим концам труб и точкам камер/источника с допуском 0,25 м. Пересечения без общего конца не становятся соединениями. Разрывы, кольца, неоднозначные группы концов и противоречащие явные ссылки не исправляются автоматически. Диаметр камеры выводится из максимального примыкающего существующего DN.

Все здания `restriction/oks`, `oks_existing` и `oks_future` участвуют в ограничениях. Исходная точка подключения не переносится. Для точки внутри единственного здания разрешён прямой конечный вход через назначенный участок внешней стены; транзит, разветвления и повороты внутри запрещены. Внутренний отрезок входит в длину и смету. Принадлежность пересекающимся зданиям вызывает `AMBIGUOUS_BUILDING_ENTRY`. Служебный признак прежней версии `_tt_future_footprint` не снимает ограничений.

Исходная БД и файл при сценарном расчёте не переписываются. Допущения применяются в представлении данных конкретного задания. Можно сравнивать сценарии загрузки без повторного импорта.

## Диагностика

Каждая запись содержит `object_id`, `code`, `severity`, `message`. Основные коды: `MISSING_UPSTREAM`, `UNKNOWN_UPSTREAM`, `MISSING_DIAMETER`, `MISSING_EXISTING_FLOW`, `UNSPECIFIED_RESTRICTION`, `INVALID_FEATURE`, `INVALID_DEMAND`. Максимум 10 000 подробных записей; при превышении добавляется сообщение о сокращении списка.

Ошибка геометрии отклоняет импорт с ID проблемного объекта. Ошибки топологии и расчётных параметров не заменяются нулевыми значениями. Если полное подключение не найдено, причины сохраняются в диагностике, а готовые варианты не публикуются.

## Экспорт

Один `FeatureCollection`, WGS84, до трёх `variant_id`. Типы объектов:

- `heat_network`: start_node_id, end_node_id, flow_tph, diameter, length, laying_method, depth_start, depth_end, cost.
- `tie_in`: existing_object_id, existing_object_type, existing_diameter, required_diameter, cost.
- `heat_network_reconstruction`: existing_object_id, existing_flow_tph, added_flow_tph, calculated_flow_tph, existing_diameter, required_diameter, length, cost.
- `heat_chamber`: diameter, cost.
- `heat_chamber_reconstruction`: existing_object_id, existing_diameter, required_diameter, cost.
- `technical_node`: общие id, object_type, variant_id.
- `variant_summary`: ровно одна запись на вариант, geometry=null; слагаемые цены, длины, score, rank и unconnected_oks_ids по приложению; дополнительное поле rating — относительная оценка 0…100, где больше лучше.

На каждом объекте также присутствуют `id`, `object_type`, `variant_id`. Неизменённые точки подключения повторно не экспортируются: конечная ссылка использует исходный ID. Это правило действует и для точек внутри зданий: новые фасадные потребители не создаются. При `mode=plan` координаты двумерные, а `depth_start` и `depth_end` равны null. При `mode=depth` глубины положительны и измерены до верха труб; координаты имеют вид `[lon, lat, -h]`. Исходные точки подключения закреплены на глубине 3 м. Промежуточный Z интерполируется по горизонтальной длине. Реконструируемая существующая сеть выводится с Z=-3 по принятой плоской модели; её профиль заново не проектируется. Это условная локальная отметка, не абсолютная геодезическая высота. Входной Z не используется как модель рельефа или измеренная глубина.

Отчёт содержит `checks.originalEndpointsPreserved`, `checks.buildingEntriesValid`, `checks.buildingIntersectionsValid` (нет неразрешённых пересечений) и список `checks.permittedBuildingEntries`. В записи: `sourceEntryId`, `buildingId`, `edgeId`, `connectionCoordinates`, `wallCrossingCoordinates`, `allowedWalls` (один выбранный участок), `nearestWallDistanceToNetworkM`, `selectedWallDistanceToNetworkM`, `geometricallyNearestToNetwork`, `wallSelectionReason`, `indoorLengthM`, `includedInCost=true`, `entryRule=network_facing_exterior_wall`. Стены и координаты записаны в WGS84. Свойство сводки `indoor_connection_length` уже включено в новую длину и стоимость; повторно прибавлять его нельзя.

Внешнее поле `metadata` коллекции — разрешённый GeoJSON foreign member. Оно содержит версию правил, профиль данных и ранжирования, выбранную загрузку и результат поиска полного подключения. Временного бюджета нет. Полный расчётный отчёт — отдельный JSON с диагностикой, контрольными суммами, проверками и настройками. Его нельзя путать с конкурсным геометрическим результатом.


## Правило 27.09.2026: подключение через наружную стену со стороны теплотрассы

`search.buildingEntryPolicy=network_facing_exterior_wall`. `search.entryAssignments` содержит для каждого исходного ввода `sourceEntryId`, `buildingId`, число `eligibleWallCount` и координаты `candidateWalls` пригодных наружных окон в WGS84. Фактическую стену вариант выбирает при прокладке, фиксирует в `checks.permittedBuildingEntries` и повторно проверяет при восстановлении сети. `network_facing_wall_penalty_m` в сводке влияет только на рейтинг; рублёвая смета и фактическая длина труб его не включают. Подробности — `BUILDINGS.md`.

## История 18.09.2026: фиксированная ближайшая стена (до версии 1.9.2)

`search.buildingEntryPolicy=nearest_exterior_wall_fixed`. `search.entryAssignments` содержит для каждого исходного ввода `sourceEntryId`, `buildingId`, `fixedWall` (исходный отрезок WGS84) и `nearestWallDistanceM`; при точке вне/на границе здания окно не требуется. Назначение сохраняется и при пустом списке вариантов. `checks.permittedBuildingEntries[].entryRule` подтверждает ту же политику.

В сводке варианта добавлены `base_score`, `turn_penalty_m`, `turn_penalty_score`, `ranking_length`, `turn_90_count`, `turn_45_count`, `turn_135_count`, `other_turn_count`, `connection_turn_count`. Числа поворотов включают углы присоединений к принимающей оси. `ranking_length=length+turn_penalty_m` — условный показатель, не фактическая длина или расход материала. `score=base_score+turn_penalty_score`, меньший лучше. `rating` остаётся относительной обратной оценкой среди полных вариантов.

`checks.turnPenalty` и `routingQuality` описывают веса. 90° — 25 условных м; 45° — 50; 135° — 75; прямая — 0. `includedInMonetaryCost=false`. `calculated_cost`, статьи сметы, `new_network_length` и `new_pipe_material_length` продолжают отражать реальные длины и денежные суммы. `routeExplanations` содержит отдельные количества поворотов, `turnPenaltyM` внутри ребра и `connectionPenaltyM` его присоединения. Это параметры предпочтения алгоритма, не строительные нормативы.
