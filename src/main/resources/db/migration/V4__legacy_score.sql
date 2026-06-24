create table legacy_score
(
    label   varchar(255) primary key,
    points  int not null default 0,
    matches int not null default 0
);
