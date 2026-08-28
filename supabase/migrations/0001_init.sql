-- Sankranthi — initial schema.
--
-- Run this once against a fresh Supabase project (SQL Editor, or
-- `supabase db push` if you use the CLI). It creates the profile/approval model,
-- the two ledgers, and the row-level security that actually enforces the
-- permissions the admin panel hands out. The client is never trusted to enforce
-- them — the UI only hides what the database would refuse anyway.

-- ---------------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------------

create type public.app_role as enum ('admin', 'member');

create type public.access_status as enum ('pending', 'approved', 'rejected');

create type public.trade_kind as enum ('buy', 'sell');

create type public.expense_category as enum (
    'feed', 'veterinary', 'labour', 'transport', 'shed_repair', 'utilities', 'other'
);

-- ---------------------------------------------------------------------------
-- Profiles: one row per Google account that has ever signed in
-- ---------------------------------------------------------------------------

create table public.profiles (
    id           uuid primary key references auth.users (id) on delete cascade,
    email        text not null,
    full_name    text,
    avatar_url   text,
    role         public.app_role not null default 'member',
    status       public.access_status not null default 'pending',
    -- Fine-grained edit rights. Values must match Permission.wire in the app:
    -- 'edit_livestock', 'edit_expenses', 'delete_entries'.
    permissions  text[] not null default '{}',
    requested_at timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

comment on column public.profiles.permissions is
    'Subset of edit_livestock, edit_expenses, delete_entries. Ignored for admins, who hold all rights.';

create index profiles_status_idx on public.profiles (status);

-- ---------------------------------------------------------------------------
-- Signup hook: every new account becomes a pending request
-- ---------------------------------------------------------------------------

-- The very first account to sign in is made an approved admin — otherwise there
-- would be nobody able to approve anyone, and the app would deadlock.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    first_user boolean;
begin
    select count(*) = 0 into first_user from public.profiles;

    insert into public.profiles (id, email, full_name, avatar_url, role, status)
    values (
        new.id,
        coalesce(new.email, ''),
        new.raw_user_meta_data ->> 'full_name',
        new.raw_user_meta_data ->> 'avatar_url',
        case when first_user then 'admin'::public.app_role else 'member'::public.app_role end,
        case when first_user then 'approved'::public.access_status else 'pending'::public.access_status end
    )
    on conflict (id) do nothing;

    return new;
end;
$$;

create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------------------
-- Permission helpers
-- ---------------------------------------------------------------------------

-- These are SECURITY DEFINER so that policies on `profiles` can read `profiles`
-- without re-entering RLS and recursing.

create or replace function public.is_admin()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from public.profiles
        where id = auth.uid()
          and role = 'admin'
          and status = 'approved'
    );
$$;

create or replace function public.is_approved()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from public.profiles
        where id = auth.uid() and status = 'approved'
    );
$$;

create or replace function public.has_permission(required text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from public.profiles
        where id = auth.uid()
          and status = 'approved'
          and (role = 'admin' or required = any (permissions))
    );
$$;

-- ---------------------------------------------------------------------------
-- Guard rails on profile edits
-- ---------------------------------------------------------------------------

-- Stops an admin from demoting or suspending their own account, which is the
-- easy way to lock every partner out of the books.
create or replace function public.guard_profile_update()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if new.id = auth.uid()
       and (new.role is distinct from old.role or new.status is distinct from old.status)
    then
        raise exception 'You cannot change your own role or access status.';
    end if;

    if not (new.permissions <@ array['edit_livestock', 'edit_expenses', 'delete_entries']::text[]) then
        raise exception 'Unknown permission in %', new.permissions;
    end if;

    new.updated_at := now();
    return new;
end;
$$;

create trigger profiles_guard_update
    before update on public.profiles
    for each row execute function public.guard_profile_update();

-- ---------------------------------------------------------------------------
-- Ledger: livestock trades
-- ---------------------------------------------------------------------------

create table public.livestock_entries (
    id              uuid primary key default gen_random_uuid(),
    kind            public.trade_kind not null,
    animal          text not null check (length(trim(animal)) > 0),
    head_count      integer not null check (head_count > 0),
    -- Money is stored in paise so totals stay exact.
    amount_minor    bigint not null check (amount_minor >= 0),
    counterparty    text,
    occurred_on     date not null,
    notes           text,
    created_by      uuid not null default auth.uid() references public.profiles (id),
    created_by_name text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create index livestock_entries_occurred_on_idx on public.livestock_entries (occurred_on desc);

-- ---------------------------------------------------------------------------
-- Ledger: organisation maintenance expenses
-- ---------------------------------------------------------------------------

create table public.expenses (
    id              uuid primary key default gen_random_uuid(),
    category        public.expense_category not null,
    amount_minor    bigint not null check (amount_minor >= 0),
    description     text,
    occurred_on     date not null,
    created_by      uuid not null default auth.uid() references public.profiles (id),
    created_by_name text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create index expenses_occurred_on_idx on public.expenses (occurred_on desc);

-- ---------------------------------------------------------------------------
-- Attribution: the server decides who authored a row, not the client
-- ---------------------------------------------------------------------------

create or replace function public.stamp_author()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if tg_op = 'INSERT' then
        new.created_by := auth.uid();
        select coalesce(nullif(trim(p.full_name), ''), split_part(p.email, '@', 1))
          into new.created_by_name
          from public.profiles p
         where p.id = auth.uid();
    else
        -- Authorship is immutable across updates.
        new.created_by := old.created_by;
        new.created_by_name := old.created_by_name;
        new.updated_at := now();
    end if;
    return new;
end;
$$;

create trigger livestock_entries_stamp_author
    before insert or update on public.livestock_entries
    for each row execute function public.stamp_author();

create trigger expenses_stamp_author
    before insert or update on public.expenses
    for each row execute function public.stamp_author();

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

alter table public.profiles enable row level security;
alter table public.livestock_entries enable row level security;
alter table public.expenses enable row level security;

-- Profiles: you can always read yourself (needed to learn you are pending);
-- admins read and write everyone.
create policy profiles_select_self on public.profiles
    for select using (id = auth.uid() or public.is_admin());

create policy profiles_update_admin on public.profiles
    for update using (public.is_admin()) with check (public.is_admin());

-- No client-side insert or delete: the signup trigger owns row creation, and
-- accounts are cascaded from auth.users.

-- Livestock: every approved member reads; editing needs the granted right.
create policy livestock_select_approved on public.livestock_entries
    for select using (public.is_approved());

create policy livestock_insert on public.livestock_entries
    for insert with check (public.has_permission('edit_livestock'));

create policy livestock_update on public.livestock_entries
    for update using (public.has_permission('edit_livestock'))
    with check (public.has_permission('edit_livestock'));

create policy livestock_delete on public.livestock_entries
    for delete using (public.has_permission('delete_entries'));

-- Expenses: same shape, keyed on the expenses right.
create policy expenses_select_approved on public.expenses
    for select using (public.is_approved());

create policy expenses_insert on public.expenses
    for insert with check (public.has_permission('edit_expenses'));

create policy expenses_update on public.expenses
    for update using (public.has_permission('edit_expenses'))
    with check (public.has_permission('edit_expenses'));

create policy expenses_delete on public.expenses
    for delete using (public.has_permission('delete_entries'));

-- ---------------------------------------------------------------------------
-- Promoting an admin by hand
-- ---------------------------------------------------------------------------
-- If the first-signup rule did not put the right person in charge, run this as
-- the service role (SQL Editor is fine):
--
--   update public.profiles
--      set role = 'admin', status = 'approved'
--    where email = 'you@example.com';
