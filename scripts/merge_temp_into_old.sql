-- =====================================================================
--  Слияние временной БД (резервная VM) в старую (каноническую).
--  Направление: temp  ->  old.   Правило конфликта ставок: новейший updated_at.
--  Слияние идёт по НАТУРАЛЬНЫМ ключам (users.id = telegram id,
--  matches.id = id из FIFA-фида, bets по (user_id, match_id)), а НЕ по
--  bigserial-id, чтобы суррогатные id двух баз не сталкивались.
-- =====================================================================
--
--  ВАЖНО: таблицу legacy_score (витрина стартовых очков на временной VM)
--  НЕ импортировать — в старой БД уже есть реальная история, иначе двойной счёт.
--  На старой БД после слияния: truncate legacy_score; (оставить пустой).
--
--  ШАГ 0. Снять бэкап старой БД ПЕРЕД слиянием:
--     pg_dump -Fc -d bet > old_before_merge.dump
--
--  ШАГ 1. Выгрузить таблицы из ВРЕМЕННОЙ БД в CSV (на temp-хосте):
--     \copy users             to 'tmp_users.csv'    csv header
--     \copy matches           to 'tmp_matches.csv'  csv header
--     \copy bets              to 'tmp_bets.csv'     csv header
--     \copy bet_score_changes to 'tmp_bsc.csv'      csv header
--
--  ШАГ 2. На СТАРОЙ БД создать staging-схему и залить CSV (см. блок ниже),
--         затем выполнить MERGE. Всё в одной транзакции.
-- =====================================================================

begin;

-- ---- staging-схема под данные временной БД ----
drop schema if exists temp_import cascade;
create schema temp_import;

create table temp_import.users             (like public.users             including all);
create table temp_import.matches           (like public.matches           including all);
create table temp_import.bets              (like public.bets              including all);
create table temp_import.bet_score_changes (like public.bet_score_changes including all);

-- ---- ЗАЛИВКА CSV (выполнить из psql на старой БД) ----
-- \copy temp_import.users             from 'tmp_users.csv'   csv header
-- \copy temp_import.matches           from 'tmp_matches.csv' csv header
-- \copy temp_import.bets              from 'tmp_bets.csv'    csv header
-- \copy temp_import.bet_score_changes from 'tmp_bsc.csv'     csv header
-- (после заливки CSV — продолжить выполнение MERGE ниже)

-- =====================================================================
--  MERGE
-- =====================================================================

-- 1) USERS: обновляем контакты/имя из временной (там свежие chat_id и активность).
insert into public.users (id, chat_id, username, first_name, status, created_at)
select id, chat_id, username, first_name, status, created_at
from temp_import.users
on conflict (id) do update set
    chat_id    = excluded.chat_id,
    username   = excluded.username,
    first_name = excluded.first_name,
    status     = excluded.status;

-- 2) MATCHES: берём счёт/статус оттуда, где матч сыгран (COALESCE на непустой счёт).
insert into public.matches (id, stage, date, home_squad_name, away_squad_name, home_score, away_score, status)
select id, stage, date, home_squad_name, away_squad_name, home_score, away_score, status
from temp_import.matches
on conflict (id) do update set
    stage           = excluded.stage,
    date            = excluded.date,
    home_squad_name = excluded.home_squad_name,
    away_squad_name = excluded.away_squad_name,
    home_score      = coalesce(public.matches.home_score, excluded.home_score),
    away_score      = coalesce(public.matches.away_score, excluded.away_score),
    status          = case when public.matches.home_score is null and excluded.home_score is not null
                           then excluded.status else public.matches.status end;

-- 3) BETS: конфликт (user_id, match_id) -> побеждает НОВЕЙШИЙ по updated_at.
--    Если временная ставка старее существующей — не трогаем (WHERE отсекает).
insert into public.bets (user_id, match_id, message_id, home_score, away_score,
                         points, result_notified, reminder_sent, created_at, updated_at)
select user_id, match_id, message_id, home_score, away_score,
       points, result_notified, reminder_sent, created_at, updated_at
from temp_import.bets
on conflict (user_id, match_id) do update set
    home_score      = excluded.home_score,
    away_score      = excluded.away_score,
    message_id      = excluded.message_id,
    points          = excluded.points,
    result_notified = public.bets.result_notified or excluded.result_notified,
    reminder_sent   = public.bets.reminder_sent   or excluded.reminder_sent,
    created_at      = least(public.bets.created_at,  excluded.created_at),
    updated_at      = greatest(public.bets.updated_at, excluded.updated_at)
where excluded.updated_at > public.bets.updated_at;

-- 4) BET_SCORE_CHANGES: дописываем аудит из временной, ремапим bet_id по (user_id, match_id).
insert into public.bet_score_changes (bet_id, user_id, match_id, home_score, away_score, changed_at)
select b.id, t.user_id, t.match_id, t.home_score, t.away_score, t.changed_at
from temp_import.bet_score_changes t
join public.bets b on b.user_id = t.user_id and b.match_id = t.match_id;

-- ---- проверка перед коммитом ----
-- select count(*) from public.bets;
-- select * from public.suspicious_bets;          -- не появились ли правки после свистка
-- table public.users limit 20;

drop schema temp_import cascade;

commit;

-- =====================================================================
--  ПОСЛЕ слияния:
--   * Очки пересчитаются сами: SyncJob идемпотентно пересчитает points
--     по всем сыгранным матчам на ближайшем тике.
--   * Очистить плашку: app.temporaryNotice = "" (или снять env TEMPORARY_NOTICE)
--     и передеплоить — уведомление о временном режиме исчезнет.
-- =====================================================================
