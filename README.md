# wc-bet-bot

Telegram-бот для прогнозов на матчи ЧМ-2026. Kotlin + [Kora](https://kora-projects.github.io/kora-docs/ru/) 1.2.16, PostgreSQL, Flyway, Docker.

## Как это работает

- `SyncJob` раз в минуту тянет расписание и результаты с `https://play.fifa.com/json/bracket_predictor/rounds.json`
  (тот же эндпоинт, что в старом bet-app — уже отдаёт ЧМ-2026), апсертит матчи и идемпотентно пересчитывает очки.
- `NotificationJob` раз в минуту шлёт активным игрокам матчи, начинающиеся в ближайшие 24 часа (настраивается,
  `app.notifyHorizonHours`), по которым у игрока ещё нет ставки. Счёт ставится инлайн-кнопками ➖/➕.
- Ставка фиксируется в момент начала матча: после `match.date` колбэки отклоняются.
- Очки (по счёту основного времени): 0 — не угадал победителя; 1 — угадал победителя; 2 — победитель и точный счёт;
  2 — угадал ничью, но не счёт; 3 — ничья с точным счётом.
- `/matches` — заново присылает карточки всех ближайших матчей с текущим прогнозом (не нужно искать старые сообщения).
- `/my` — сводка своих ставок на сегодня одним сообщением (🕒 не начался / 🔒 идёт / 🏁 завершён с очками).
- `/table` — таблица игроков по убыванию очков. `/start` — подписка, `/stop` — отписка.
- Команды зарегистрированы в меню Telegram (кнопка «Меню» слева от поля ввода).
- За час до матча (`app.reminderMinutes`) приходит напоминание, если прогноз остался 0:0.
- Утром (`app.digestTime`, по умолчанию 09:00) — дайджест: вчерашние результаты + таблица одним сообщением.
- В групповом чате работает `/table` — добавь бота в общий чат, чтобы вся компания видела таблицу.
- После завершения матча (статус из `app.finishedStatuses`) игроку приходит результат и начисленные очки.

## Запуск на сервере

```bash
cp .env.example .env   # вписать BOT_TOKEN (от @BotFather) и BOT_NAME
docker compose up -d --build
docker compose logs -f app
```

Приложение собирается прямо в Docker (multi-stage, gradle 8.7 + JDK 17) — на сервере нужен только Docker.

## Локальная разработка

```bash
docker compose up -d postgres          # Postgres на localhost:15432
BOT_TOKEN=... BOT_NAME=... ./gradlew run
./gradlew test                          # тесты подсчёта очков
```

## Конфигурация (env)

| Переменная     | Описание                              | По умолчанию                            |
|----------------|---------------------------------------|-----------------------------------------|
| `BOT_TOKEN`    | токен бота от @BotFather              | — (обязательно)                         |
| `BOT_NAME`     | username бота                         | — (обязательно)                         |
| `DB_URL`       | JDBC URL Postgres                     | `jdbc:postgresql://localhost:15432/bet` |
| `DB_USER`      | пользователь БД                       | `bet_user`                              |
| `DB_PASSWORD`  | пароль БД                             | `password`                              |
| `APP_ZONE`     | часовой пояс для отображения времени  | `Europe/Moscow`                         |

⚠️ В старом `bet-app` токен бота закоммичен в `application.yml` — он засвечен, отзови его в @BotFather
(`/revoke`) и для нового бота храни токен только в `.env`.

## Структура

```
src/main/kotlin/wcbet/
├── Application.kt        # @KoraApp + main
├── config/Configs.kt     # BotConfig, AppConfig (@ConfigSource)
├── fifa/FifaApi.kt       # декларативный HTTP-клиент + DTO
├── model/Models.kt       # сущности БД (@EntityJdbc)
├── repo/Repositories.kt  # @Repository + @Query
├── service/ScoreCalculator.kt
├── telegram/BetBot.kt    # команды и колбэки
├── telegram/BotRegistrar.kt
└── job/Jobs.kt           # SyncJob, NotificationJob
```
