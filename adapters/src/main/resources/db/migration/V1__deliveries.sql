-- One row per order. The plan (when each step happens) is fixed at creation; status is how far the
-- simulation has followed it. next_step_at drives the ticker: SKIP LOCKED lets every pod share the work.
create table deliveries
(
    order_id            text primary key,
    user_id             text             not null,
    restaurant_name     text             not null,
    pickup_latitude     double precision not null,
    pickup_longitude    double precision not null,
    drop_off_latitude   double precision not null,
    drop_off_longitude  double precision not null,
    drop_off_label      text,
    courier_name        text             not null,
    courier_vehicle     text             not null,
    -- Polylines as flat [lat, lng, lat, lng, ...]
    approach_route      double precision[] not null,
    delivery_route      double precision[] not null,
    accepted_at         timestamptz      not null,
    cooking_at          timestamptz      not null,
    picked_up_at        timestamptz      not null,
    en_route_at         timestamptz      not null,
    delivered_at        timestamptz      not null,
    status              text             not null,
    next_step_at        timestamptz,
    position_published_at timestamptz    not null default '-infinity'
);

create index deliveries_next_step on deliveries (next_step_at) where next_step_at is not null;
create index deliveries_user on deliveries (user_id);
