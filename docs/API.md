# API · 1.9.5

Базовый адрес `/api/v1`; интерактивная схема доступна по `/tree-1.9.5`. Контракт OpenAPI: `/v3/api-docs`.

1. `POST /datasets?name=map.geojson` с бинарным телом GeoJSON и заголовком `Content-Type: application/geo+json` создаёт набор; возвращает `id`.
2. `GET /datasets/{id}` возвращает состояние импорта и диагностику. `READY` означает завершение импорта, `strictReady` — отсутствие ошибок в обязательных полях.
3. `POST /datasets/{id}/jobs` с настройками создаёт фоновый расчёт; возвращает ID задания.
4. `GET /jobs/{id}` возвращает состояние, прогресс и при завершении отчёт. `GET /jobs/{id}/result` отдаёт GeoJSON; `GET /jobs/{id}/report` — подробный JSON.

Пример настроек конкурса:

```json
{
  "dataMode": "strict",
  "rankingProfile": "contest",
  "mode": "plan",
  "routingStrategy": "tree",
  "neuralGuidance": "baseline",
  "treeSingleRootRequired": true,
  "variantLimit": 3,
  "candidateLimit": 8
}
```

Файл содержит один `FeatureCollection` WGS84 с обязательными объектами `source` (`Point`), `heat_network` (`LineString`, `diameter`), `heat_chamber` (`Point`), `oks_connection_point` (`Point`, `flow_tph`) и `restriction` (геометрия согласно типу). Полигоны ОКС указываются как `restriction_type=oks`. Каждая точка подключения учитывается самостоятельно, в том числе несколько точек внутри одного полигона. Ссылки, расход и диаметр **существующих** камер не обязательны для `rankingProfile=contest`. Числовые ID сохраняют тип в выходных ссылках.

При `mode=depth` задаются `minDepthM` (не менее 0,7) и при необходимости `maxDepthM` (по умолчанию 6; верхний предел ТЗ не установлен). В базовом режиме `depth_start` и `depth_end` равны `null`. Профили, пересечения, допущения и причины недоступных вводов находятся в отчёте.

Результат содержит только `heat_network`, `heat_chamber`, `technical_node`, `variant_summary`. Для сводки `construction_cost` включает новые трубы, камеры и врезки по 5 млн ₽ за каждый новый участок в существующей камере; `unconnected_penalty` равен сумме `100 млн + 0,5 млн × flow_tph` по неподключённым точкам; `calculated_cost` равен их сумме; `score = 0,7 × calculated_cost / 25 млн + 0,3 × new_network_length / 100`. В `unconnected_oks_ids` находятся исходные ID точек с исходным JSON-типом. До трёх вариантов ранжируются по `score` (меньше лучше); геометрически похожие варианты не дублируются.

Дополнительный `rankingProfile=appendix` оставлен для внутренней расширенной инженерной оценки с текущими расходами и топологией существующей сети. Его результаты и показатель не следует выдавать за конкурсную сводку.
