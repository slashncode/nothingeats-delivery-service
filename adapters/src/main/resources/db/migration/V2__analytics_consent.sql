-- The customer's analytics consent at checkout (from the placed event): server-side analytics
-- about the delivery, and the consent in its status events, follow it. Older rows: no consent.
alter table deliveries
    add column analytics boolean not null default false;
