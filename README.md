# Теплотрасса AI · 1.9.5

## Запуск

Распакуйте архив целиком. Для обновления используйте прежнее имя Compose-проекта, чтобы сохранить тома и базу, и перенесите старый `.env`, если меняли параметры БД:

```powershell
$env:PORT = "8082"
docker compose -f .\docker-compose.yml -p teplotrassa-tree151 up -d --build
```

Откройте <http://localhost:8082/tree-1.9.5>. Если Docker Hub отвечает по HTTP вместо HTTPS при запросе токена, Docker-сборка прервётся до компиляции проекта: проверьте настройки прокси и доступ Docker к `auth.docker.io`. Для запуска готового JAR вне Docker нужны Java 11 и PostgreSQL, параметры базы указаны в `server/src/main/resources/application.yml`.
