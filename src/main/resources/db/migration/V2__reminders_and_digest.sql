alter table bets
    add column reminder_sent boolean not null default false;

create table digest_log
(
    day date primary key
);
