alter table bets
    add column created_at timestamptz not null default now(),
    add column updated_at timestamptz not null default now();

create table bet_score_changes
(
    id         bigserial primary key,
    bet_id     bigint      not null references bets (id),
    user_id    bigint      not null references users (id),
    match_id   int         not null references matches (id),
    home_score int         not null,
    away_score int         not null,
    changed_at timestamptz not null default now()
);

create index bet_score_changes_bet_idx on bet_score_changes (bet_id);

create view suspicious_bets as
select b.id            as bet_id,
       coalesce(u.first_name, u.username, u.id::text) as player,
       m.home_squad_name,
       m.away_squad_name,
       m.date          as kickoff,
       b.updated_at,
       b.updated_at - m.date as edited_after_kickoff,
       b.home_score || ':' || b.away_score as bet,
       m.home_score || ':' || m.away_score as result,
       b.points
from bets b
         join users u on u.id = b.user_id
         join matches m on m.id = b.match_id
where b.updated_at > m.date
order by b.updated_at desc;
