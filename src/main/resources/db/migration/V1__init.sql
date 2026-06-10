create table users
(
    id         bigint primary key,
    chat_id    bigint       not null,
    username   varchar(255),
    first_name varchar(255),
    status     varchar(32)  not null default 'ACTIVE',
    created_at timestamptz  not null default now()
);

create table matches
(
    id              int primary key,
    stage           varchar(32)  not null,
    date            timestamptz  not null,
    home_squad_name varchar(255) not null,
    away_squad_name varchar(255) not null,
    home_score      int,
    away_score      int,
    status          varchar(32)  not null
);

create table bets
(
    id              bigserial primary key,
    user_id         bigint  not null references users (id),
    match_id        int     not null references matches (id),
    message_id      int     not null,
    home_score      int     not null default 0,
    away_score      int     not null default 0,
    points          int,
    result_notified boolean not null default false,
    unique (user_id, match_id)
);

create index bets_match_idx on bets (match_id);
create index matches_date_idx on matches (date);
