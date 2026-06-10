-- ================================================================
-- TeslaMate 官方 schema（schema-only pg_dump，勿手改）
--
-- 提取自: teslamate/teslamate:latest = v3.1.0
--   image digest: sha256:b7b8b21869d789d043f62b404dd689f55c601ef7d02222e92c0f5d596d0b1044
--   提取日期:     2026-06-10
--   migration:    100 条，最新 20260411070212 (ImproveBrinIndexesOnPositions)
--
-- 提取方法（官方出新版后照此刷新）:
--   1. docker run -d --name play-compat-pg -e POSTGRES_USER=teslamate \
--        -e POSTGRES_PASSWORD=ci-only-disposable -e POSTGRES_DB=teslamate \
--        -p 127.0.0.1:54329:5432 postgres:16-alpine
--   2. docker run -d --name play-compat-tm \
--        -e DATABASE_USER=teslamate -e DATABASE_PASS=ci-only-disposable \
--        -e DATABASE_NAME=teslamate -e DATABASE_HOST=host.docker.internal \
--        -e DATABASE_PORT=54329 -e ENCRYPTION_KEY=dummy0123456789 \
--        -e DISABLE_MQTT=true teslamate/teslamate:latest
--   3. 等日志出现全部 "== Migrated" 后:
--      docker exec play-compat-pg pg_dump -U teslamate -s teslamate \
--        > ops/teslamate-official-schema.sql   (重新拼上本头注释)
--   4. docker rm -f play-compat-tm play-compat-pg
--
-- 用途: bin/play-compat-test.sh 用它建库，验证 plays/*/play.yaml 的声明式
--   SQL 在【原版 teslamate-org/teslamate】schema 上语法 + 列全部有效。
--   开源 teslamate-llm-bridge 的目标用户跑原版，prod 跑 fork
--   (teslamate-multitenant，只动 private.tokens，public 表零改动)。
-- ================================================================

--
-- PostgreSQL database dump
--

\restrict YSzwCmEe8WnJJSEfwuGUVd3ByFwfTlNw1DJWwKhy1hedm3fkxUQ6j0ou9vBHydJ

-- Dumped from database version 16.14
-- Dumped by pg_dump version 16.14

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: private; Type: SCHEMA; Schema: -; Owner: teslamate
--

CREATE SCHEMA private;


ALTER SCHEMA private OWNER TO teslamate;

--
-- Name: cube; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS cube WITH SCHEMA public;


--
-- Name: EXTENSION cube; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION cube IS 'data type for multidimensional cubes';


--
-- Name: earthdistance; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS earthdistance WITH SCHEMA public;


--
-- Name: EXTENSION earthdistance; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION earthdistance IS 'calculate great-circle distances on the surface of the Earth';


--
-- Name: billing_type; Type: TYPE; Schema: public; Owner: teslamate
--

CREATE TYPE public.billing_type AS ENUM (
    'per_kwh',
    'per_minute'
);


ALTER TYPE public.billing_type OWNER TO teslamate;

--
-- Name: range; Type: TYPE; Schema: public; Owner: teslamate
--

CREATE TYPE public.range AS ENUM (
    'ideal',
    'rated'
);


ALTER TYPE public.range OWNER TO teslamate;

--
-- Name: states_status; Type: TYPE; Schema: public; Owner: teslamate
--

CREATE TYPE public.states_status AS ENUM (
    'online',
    'offline',
    'asleep'
);


ALTER TYPE public.states_status OWNER TO teslamate;

--
-- Name: unit_of_length; Type: TYPE; Schema: public; Owner: teslamate
--

CREATE TYPE public.unit_of_length AS ENUM (
    'km',
    'mi'
);


ALTER TYPE public.unit_of_length OWNER TO teslamate;

--
-- Name: unit_of_pressure; Type: TYPE; Schema: public; Owner: teslamate
--

CREATE TYPE public.unit_of_pressure AS ENUM (
    'bar',
    'psi'
);


ALTER TYPE public.unit_of_pressure OWNER TO teslamate;

--
-- Name: unit_of_temperature; Type: TYPE; Schema: public; Owner: teslamate
--

CREATE TYPE public.unit_of_temperature AS ENUM (
    'C',
    'F'
);


ALTER TYPE public.unit_of_temperature OWNER TO teslamate;

--
-- Name: convert_celsius(numeric, text); Type: FUNCTION; Schema: public; Owner: teslamate
--

CREATE FUNCTION public.convert_celsius(n numeric, unit text) RETURNS numeric
    LANGUAGE sql
    AS $_$
  SELECT
  CASE $2 WHEN 'C' THEN $1
          WHEN 'F' THEN ($1 * 9 / 5) + 32
  END;
$_$;


ALTER FUNCTION public.convert_celsius(n numeric, unit text) OWNER TO teslamate;

--
-- Name: convert_km(numeric, text); Type: FUNCTION; Schema: public; Owner: teslamate
--

CREATE FUNCTION public.convert_km(n numeric, unit text) RETURNS numeric
    LANGUAGE sql
    AS $_$
  SELECT
  CASE $2 WHEN 'km' THEN $1
          WHEN 'mi' THEN $1 / 1.60934
  END;
$_$;


ALTER FUNCTION public.convert_km(n numeric, unit text) OWNER TO teslamate;

--
-- Name: convert_m(double precision, text); Type: FUNCTION; Schema: public; Owner: teslamate
--

CREATE FUNCTION public.convert_m(n double precision, unit text) RETURNS double precision
    LANGUAGE sql IMMUTABLE STRICT
    AS $_$
  SELECT
    CASE WHEN $2 = 'm' THEN $1
         WHEN $2 = 'ft' THEN $1 * 3.28084
    END;
$_$;


ALTER FUNCTION public.convert_m(n double precision, unit text) OWNER TO teslamate;

--
-- Name: convert_tire_pressure(numeric, character varying); Type: FUNCTION; Schema: public; Owner: teslamate
--

CREATE FUNCTION public.convert_tire_pressure(n numeric, character varying) RETURNS numeric
    LANGUAGE sql
    AS $_$
SELECT
CASE $2 WHEN 'bar' THEN $1
    WHEN 'psi' THEN $1 * 14.503773773
END;
$_$;


ALTER FUNCTION public.convert_tire_pressure(n numeric, character varying) OWNER TO teslamate;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: tokens; Type: TABLE; Schema: private; Owner: teslamate
--

CREATE TABLE private.tokens (
    id integer NOT NULL,
    inserted_at timestamp(0) without time zone NOT NULL,
    updated_at timestamp(0) without time zone NOT NULL,
    refresh bytea,
    access bytea
);


ALTER TABLE private.tokens OWNER TO teslamate;

--
-- Name: tokens_id_seq; Type: SEQUENCE; Schema: private; Owner: teslamate
--

CREATE SEQUENCE private.tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE private.tokens_id_seq OWNER TO teslamate;

--
-- Name: tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: private; Owner: teslamate
--

ALTER SEQUENCE private.tokens_id_seq OWNED BY private.tokens.id;


--
-- Name: addresses; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.addresses (
    id integer NOT NULL,
    display_name character varying(512),
    latitude numeric(8,6),
    longitude numeric(9,6),
    name character varying(255),
    house_number character varying(255),
    road character varying(255),
    neighbourhood character varying(255),
    city character varying(255),
    county character varying(255),
    postcode character varying(255),
    state character varying(255),
    state_district character varying(255),
    country character varying(255),
    raw jsonb,
    inserted_at timestamp(0) without time zone NOT NULL,
    updated_at timestamp(0) without time zone NOT NULL,
    osm_id bigint,
    osm_type text
);


ALTER TABLE public.addresses OWNER TO teslamate;

--
-- Name: addresses_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.addresses_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.addresses_id_seq OWNER TO teslamate;

--
-- Name: addresses_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.addresses_id_seq OWNED BY public.addresses.id;


--
-- Name: car_settings; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.car_settings (
    id bigint NOT NULL,
    suspend_min integer DEFAULT 21 NOT NULL,
    suspend_after_idle_min integer DEFAULT 15 NOT NULL,
    req_not_unlocked boolean DEFAULT false NOT NULL,
    free_supercharging boolean DEFAULT false NOT NULL,
    use_streaming_api boolean DEFAULT true NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    lfp_battery boolean DEFAULT false NOT NULL
);


ALTER TABLE public.car_settings OWNER TO teslamate;

--
-- Name: car_settings_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.car_settings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.car_settings_id_seq OWNER TO teslamate;

--
-- Name: car_settings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.car_settings_id_seq OWNED BY public.car_settings.id;


--
-- Name: cars; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.cars (
    id smallint NOT NULL,
    eid bigint NOT NULL,
    vid bigint NOT NULL,
    model character varying(255),
    efficiency double precision,
    inserted_at timestamp(0) without time zone NOT NULL,
    updated_at timestamp(0) without time zone NOT NULL,
    vin text,
    name text,
    trim_badging text,
    settings_id bigint NOT NULL,
    exterior_color text,
    spoiler_type text,
    wheel_type text,
    display_priority smallint DEFAULT 1 NOT NULL,
    marketing_name character varying(255)
);


ALTER TABLE public.cars OWNER TO teslamate;

--
-- Name: cars_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.cars_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.cars_id_seq OWNER TO teslamate;

--
-- Name: cars_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.cars_id_seq OWNED BY public.cars.id;


--
-- Name: charges; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.charges (
    id integer NOT NULL,
    date timestamp without time zone NOT NULL,
    battery_heater_on boolean,
    battery_level smallint,
    charge_energy_added numeric(8,2) NOT NULL,
    charger_actual_current smallint,
    charger_phases smallint,
    charger_pilot_current smallint,
    charger_power smallint NOT NULL,
    charger_voltage smallint,
    fast_charger_present boolean,
    conn_charge_cable character varying(255),
    fast_charger_brand character varying(255),
    fast_charger_type character varying(255),
    ideal_battery_range_km numeric(6,2) NOT NULL,
    not_enough_power_to_heat boolean,
    outside_temp numeric(4,1),
    charging_process_id integer NOT NULL,
    battery_heater boolean,
    battery_heater_no_power boolean,
    rated_battery_range_km numeric(6,2),
    usable_battery_level smallint
);


ALTER TABLE public.charges OWNER TO teslamate;

--
-- Name: charges_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.charges_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.charges_id_seq OWNER TO teslamate;

--
-- Name: charges_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.charges_id_seq OWNED BY public.charges.id;


--
-- Name: charging_processes; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.charging_processes (
    id integer NOT NULL,
    start_date timestamp without time zone NOT NULL,
    end_date timestamp without time zone,
    charge_energy_added numeric(8,2),
    start_ideal_range_km numeric(6,2),
    end_ideal_range_km numeric(6,2),
    start_battery_level smallint,
    end_battery_level smallint,
    duration_min smallint,
    outside_temp_avg numeric(4,1),
    car_id smallint NOT NULL,
    position_id integer NOT NULL,
    address_id integer,
    start_rated_range_km numeric(6,2),
    end_rated_range_km numeric(6,2),
    geofence_id integer,
    charge_energy_used numeric(8,2),
    cost numeric(6,2)
);


ALTER TABLE public.charging_processes OWNER TO teslamate;

--
-- Name: charging_processes_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.charging_processes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.charging_processes_id_seq OWNER TO teslamate;

--
-- Name: charging_processes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.charging_processes_id_seq OWNED BY public.charging_processes.id;


--
-- Name: drives; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.drives (
    id integer NOT NULL,
    start_date timestamp without time zone NOT NULL,
    end_date timestamp without time zone,
    outside_temp_avg numeric(4,1),
    speed_max smallint,
    power_max smallint,
    power_min smallint,
    start_ideal_range_km numeric(6,2),
    end_ideal_range_km numeric(6,2),
    start_km double precision,
    end_km double precision,
    distance double precision,
    duration_min smallint,
    car_id smallint NOT NULL,
    inside_temp_avg numeric(4,1),
    start_address_id integer,
    end_address_id integer,
    start_rated_range_km numeric(6,2),
    end_rated_range_km numeric(6,2),
    start_position_id integer,
    end_position_id integer,
    start_geofence_id integer,
    end_geofence_id integer,
    ascent smallint,
    descent smallint
);


ALTER TABLE public.drives OWNER TO teslamate;

--
-- Name: geofences; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.geofences (
    id integer NOT NULL,
    name character varying(255) NOT NULL,
    latitude numeric(8,6) NOT NULL,
    longitude numeric(9,6) NOT NULL,
    radius smallint DEFAULT 25 NOT NULL,
    inserted_at timestamp(0) without time zone NOT NULL,
    updated_at timestamp(0) without time zone NOT NULL,
    cost_per_unit numeric(6,4),
    session_fee numeric(6,2),
    billing_type public.billing_type DEFAULT 'per_kwh'::public.billing_type NOT NULL
);


ALTER TABLE public.geofences OWNER TO teslamate;

--
-- Name: geofences_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.geofences_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.geofences_id_seq OWNER TO teslamate;

--
-- Name: geofences_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.geofences_id_seq OWNED BY public.geofences.id;


--
-- Name: positions; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.positions (
    id integer NOT NULL,
    date timestamp without time zone NOT NULL,
    latitude numeric(8,6) NOT NULL,
    longitude numeric(9,6) NOT NULL,
    speed smallint,
    power smallint,
    odometer double precision,
    ideal_battery_range_km numeric(6,2),
    battery_level smallint,
    outside_temp numeric(4,1),
    elevation smallint,
    fan_status integer,
    driver_temp_setting numeric(4,1),
    passenger_temp_setting numeric(4,1),
    is_climate_on boolean,
    is_rear_defroster_on boolean,
    is_front_defroster_on boolean,
    car_id smallint NOT NULL,
    drive_id integer,
    inside_temp numeric(4,1),
    battery_heater boolean,
    battery_heater_on boolean,
    battery_heater_no_power boolean,
    est_battery_range_km numeric(6,2),
    rated_battery_range_km numeric(6,2),
    usable_battery_level smallint,
    tpms_pressure_fl numeric(4,1),
    tpms_pressure_fr numeric(4,1),
    tpms_pressure_rl numeric(4,1),
    tpms_pressure_rr numeric(4,1)
);


ALTER TABLE public.positions OWNER TO teslamate;

--
-- Name: positions_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.positions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.positions_id_seq OWNER TO teslamate;

--
-- Name: positions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.positions_id_seq OWNED BY public.positions.id;


--
-- Name: schema_migrations; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.schema_migrations (
    version bigint NOT NULL,
    inserted_at timestamp(0) without time zone
);


ALTER TABLE public.schema_migrations OWNER TO teslamate;

--
-- Name: settings; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.settings (
    id bigint NOT NULL,
    inserted_at timestamp(0) without time zone NOT NULL,
    updated_at timestamp(0) without time zone NOT NULL,
    unit_of_length public.unit_of_length DEFAULT 'km'::public.unit_of_length NOT NULL,
    unit_of_temperature public.unit_of_temperature DEFAULT 'C'::public.unit_of_temperature NOT NULL,
    preferred_range public.range DEFAULT 'rated'::public.range NOT NULL,
    base_url character varying(255),
    grafana_url character varying(255),
    language text DEFAULT 'en'::text NOT NULL,
    unit_of_pressure public.unit_of_pressure DEFAULT 'bar'::public.unit_of_pressure NOT NULL,
    theme_mode text DEFAULT 'system'::text NOT NULL
);


ALTER TABLE public.settings OWNER TO teslamate;

--
-- Name: settings_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.settings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.settings_id_seq OWNER TO teslamate;

--
-- Name: settings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.settings_id_seq OWNED BY public.settings.id;


--
-- Name: states; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.states (
    id integer NOT NULL,
    state public.states_status NOT NULL,
    start_date timestamp without time zone NOT NULL,
    end_date timestamp without time zone,
    car_id smallint NOT NULL,
    CONSTRAINT positive_duration CHECK ((end_date >= start_date))
);


ALTER TABLE public.states OWNER TO teslamate;

--
-- Name: states_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.states_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.states_id_seq OWNER TO teslamate;

--
-- Name: states_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.states_id_seq OWNED BY public.states.id;


--
-- Name: trips_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.trips_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.trips_id_seq OWNER TO teslamate;

--
-- Name: trips_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.trips_id_seq OWNED BY public.drives.id;


--
-- Name: updates; Type: TABLE; Schema: public; Owner: teslamate
--

CREATE TABLE public.updates (
    id integer NOT NULL,
    start_date timestamp without time zone NOT NULL,
    end_date timestamp without time zone,
    version character varying(255),
    car_id smallint NOT NULL,
    CONSTRAINT positive_duration CHECK ((end_date >= start_date))
);


ALTER TABLE public.updates OWNER TO teslamate;

--
-- Name: updates_id_seq; Type: SEQUENCE; Schema: public; Owner: teslamate
--

CREATE SEQUENCE public.updates_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.updates_id_seq OWNER TO teslamate;

--
-- Name: updates_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: teslamate
--

ALTER SEQUENCE public.updates_id_seq OWNED BY public.updates.id;


--
-- Name: tokens id; Type: DEFAULT; Schema: private; Owner: teslamate
--

ALTER TABLE ONLY private.tokens ALTER COLUMN id SET DEFAULT nextval('private.tokens_id_seq'::regclass);


--
-- Name: addresses id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.addresses ALTER COLUMN id SET DEFAULT nextval('public.addresses_id_seq'::regclass);


--
-- Name: car_settings id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.car_settings ALTER COLUMN id SET DEFAULT nextval('public.car_settings_id_seq'::regclass);


--
-- Name: cars id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.cars ALTER COLUMN id SET DEFAULT nextval('public.cars_id_seq'::regclass);


--
-- Name: charges id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.charges ALTER COLUMN id SET DEFAULT nextval('public.charges_id_seq'::regclass);


--
-- Name: charging_processes id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.charging_processes ALTER COLUMN id SET DEFAULT nextval('public.charging_processes_id_seq'::regclass);


--
-- Name: drives id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.drives ALTER COLUMN id SET DEFAULT nextval('public.trips_id_seq'::regclass);


--
-- Name: geofences id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.geofences ALTER COLUMN id SET DEFAULT nextval('public.geofences_id_seq'::regclass);


--
-- Name: positions id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.positions ALTER COLUMN id SET DEFAULT nextval('public.positions_id_seq'::regclass);


--
-- Name: settings id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.settings ALTER COLUMN id SET DEFAULT nextval('public.settings_id_seq'::regclass);


--
-- Name: states id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.states ALTER COLUMN id SET DEFAULT nextval('public.states_id_seq'::regclass);


--
-- Name: updates id; Type: DEFAULT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.updates ALTER COLUMN id SET DEFAULT nextval('public.updates_id_seq'::regclass);


--
-- Name: tokens tokens_pkey; Type: CONSTRAINT; Schema: private; Owner: teslamate
--

ALTER TABLE ONLY private.tokens
    ADD CONSTRAINT tokens_pkey PRIMARY KEY (id);


--
-- Name: addresses addresses_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.addresses
    ADD CONSTRAINT addresses_pkey PRIMARY KEY (id);


--
-- Name: car_settings car_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.car_settings
    ADD CONSTRAINT car_settings_pkey PRIMARY KEY (id);


--
-- Name: cars cars_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.cars
    ADD CONSTRAINT cars_pkey PRIMARY KEY (id);


--
-- Name: charges charges_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.charges
    ADD CONSTRAINT charges_pkey PRIMARY KEY (id);


--
-- Name: charging_processes charging_processes_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.charging_processes
    ADD CONSTRAINT charging_processes_pkey PRIMARY KEY (id);


--
-- Name: geofences geofences_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.geofences
    ADD CONSTRAINT geofences_pkey PRIMARY KEY (id);


--
-- Name: positions positions_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT positions_pkey PRIMARY KEY (id);


--
-- Name: schema_migrations schema_migrations_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.schema_migrations
    ADD CONSTRAINT schema_migrations_pkey PRIMARY KEY (version);


--
-- Name: settings settings_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.settings
    ADD CONSTRAINT settings_pkey PRIMARY KEY (id);


--
-- Name: states states_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.states
    ADD CONSTRAINT states_pkey PRIMARY KEY (id);


--
-- Name: drives trips_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.drives
    ADD CONSTRAINT trips_pkey PRIMARY KEY (id);


--
-- Name: updates updates_pkey; Type: CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.updates
    ADD CONSTRAINT updates_pkey PRIMARY KEY (id);


--
-- Name: addresses_osm_id_osm_type_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE UNIQUE INDEX addresses_osm_id_osm_type_index ON public.addresses USING btree (osm_id, osm_type);


--
-- Name: cars_eid_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE UNIQUE INDEX cars_eid_index ON public.cars USING btree (eid);


--
-- Name: cars_settings_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE UNIQUE INDEX cars_settings_id_index ON public.cars USING btree (settings_id);


--
-- Name: cars_vid_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE UNIQUE INDEX cars_vid_index ON public.cars USING btree (vid);


--
-- Name: cars_vin_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE UNIQUE INDEX cars_vin_index ON public.cars USING btree (vin);


--
-- Name: charges_charging_process_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX charges_charging_process_id_index ON public.charges USING btree (charging_process_id);


--
-- Name: charges_date_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX charges_date_index ON public.charges USING btree (date);


--
-- Name: charging_processes_address_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX charging_processes_address_id_index ON public.charging_processes USING btree (address_id);


--
-- Name: charging_processes_car_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX charging_processes_car_id_index ON public.charging_processes USING btree (car_id);


--
-- Name: charging_processes_position_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX charging_processes_position_id_index ON public.charging_processes USING btree (position_id);


--
-- Name: drives_end_geofence_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX drives_end_geofence_id_index ON public.drives USING btree (end_geofence_id);


--
-- Name: drives_end_position_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX drives_end_position_id_index ON public.drives USING btree (end_position_id);


--
-- Name: drives_start_geofence_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX drives_start_geofence_id_index ON public.drives USING btree (start_geofence_id);


--
-- Name: drives_start_position_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX drives_start_position_id_index ON public.drives USING btree (start_position_id);


--
-- Name: positions_car_id_date__ideal_battery_range_km_IS_NOT_NULL_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX "positions_car_id_date__ideal_battery_range_km_IS_NOT_NULL_index" ON public.positions USING btree (car_id, date, ((ideal_battery_range_km IS NOT NULL))) WHERE (ideal_battery_range_km IS NOT NULL);


--
-- Name: positions_car_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX positions_car_id_index ON public.positions USING btree (car_id);


--
-- Name: positions_date_timestamp_minmax_multi_ops_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX positions_date_timestamp_minmax_multi_ops_index ON public.positions USING brin (date timestamp_minmax_multi_ops) WITH (autosummarize='true', pages_per_range='64');


--
-- Name: positions_drive_id_date_timestamp_minmax_multi_ops_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX positions_drive_id_date_timestamp_minmax_multi_ops_index ON public.positions USING brin (drive_id, date timestamp_minmax_multi_ops) WITH (autosummarize='true', pages_per_range='64');


--
-- Name: states_car_id__end_date_IS_NULL_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE UNIQUE INDEX "states_car_id__end_date_IS_NULL_index" ON public.states USING btree (car_id, ((end_date IS NULL))) WHERE (end_date IS NULL);


--
-- Name: states_car_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX states_car_id_index ON public.states USING btree (car_id);


--
-- Name: trips_car_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX trips_car_id_index ON public.drives USING btree (car_id);


--
-- Name: trips_end_address_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX trips_end_address_id_index ON public.drives USING btree (end_address_id);


--
-- Name: trips_start_address_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX trips_start_address_id_index ON public.drives USING btree (start_address_id);


--
-- Name: updates_car_id_index; Type: INDEX; Schema: public; Owner: teslamate
--

CREATE INDEX updates_car_id_index ON public.updates USING btree (car_id);


--
-- Name: cars cars_settings_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.cars
    ADD CONSTRAINT cars_settings_id_fkey FOREIGN KEY (settings_id) REFERENCES public.car_settings(id) ON DELETE CASCADE;


--
-- Name: charges charges_charging_process_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.charges
    ADD CONSTRAINT charges_charging_process_id_fkey FOREIGN KEY (charging_process_id) REFERENCES public.charging_processes(id) ON DELETE CASCADE;


--
-- Name: charging_processes charging_processes_address_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.charging_processes
    ADD CONSTRAINT charging_processes_address_id_fkey FOREIGN KEY (address_id) REFERENCES public.addresses(id) ON DELETE SET NULL;


--
-- Name: charging_processes charging_processes_car_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.charging_processes
    ADD CONSTRAINT charging_processes_car_id_fkey FOREIGN KEY (car_id) REFERENCES public.cars(id) ON DELETE CASCADE;


--
-- Name: charging_processes charging_processes_geofence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.charging_processes
    ADD CONSTRAINT charging_processes_geofence_id_fkey FOREIGN KEY (geofence_id) REFERENCES public.geofences(id) ON DELETE SET NULL;


--
-- Name: charging_processes charging_processes_position_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.charging_processes
    ADD CONSTRAINT charging_processes_position_id_fkey FOREIGN KEY (position_id) REFERENCES public.positions(id);


--
-- Name: drives drives_car_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.drives
    ADD CONSTRAINT drives_car_id_fkey FOREIGN KEY (car_id) REFERENCES public.cars(id) ON DELETE CASCADE;


--
-- Name: drives drives_end_address_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.drives
    ADD CONSTRAINT drives_end_address_id_fkey FOREIGN KEY (end_address_id) REFERENCES public.addresses(id) ON DELETE SET NULL;


--
-- Name: drives drives_end_geofence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.drives
    ADD CONSTRAINT drives_end_geofence_id_fkey FOREIGN KEY (end_geofence_id) REFERENCES public.geofences(id) ON DELETE SET NULL;


--
-- Name: drives drives_end_position_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.drives
    ADD CONSTRAINT drives_end_position_id_fkey FOREIGN KEY (end_position_id) REFERENCES public.positions(id) ON DELETE SET NULL;


--
-- Name: drives drives_start_address_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.drives
    ADD CONSTRAINT drives_start_address_id_fkey FOREIGN KEY (start_address_id) REFERENCES public.addresses(id) ON DELETE SET NULL;


--
-- Name: drives drives_start_geofence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.drives
    ADD CONSTRAINT drives_start_geofence_id_fkey FOREIGN KEY (start_geofence_id) REFERENCES public.geofences(id) ON DELETE SET NULL;


--
-- Name: drives drives_start_position_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.drives
    ADD CONSTRAINT drives_start_position_id_fkey FOREIGN KEY (start_position_id) REFERENCES public.positions(id) ON DELETE SET NULL;


--
-- Name: positions positions_car_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT positions_car_id_fkey FOREIGN KEY (car_id) REFERENCES public.cars(id) ON DELETE CASCADE;


--
-- Name: positions positions_drive_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.positions
    ADD CONSTRAINT positions_drive_id_fkey FOREIGN KEY (drive_id) REFERENCES public.drives(id) ON DELETE SET NULL;


--
-- Name: states states_car_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.states
    ADD CONSTRAINT states_car_id_fkey FOREIGN KEY (car_id) REFERENCES public.cars(id) ON DELETE CASCADE;


--
-- Name: updates updates_car_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: teslamate
--

ALTER TABLE ONLY public.updates
    ADD CONSTRAINT updates_car_id_fkey FOREIGN KEY (car_id) REFERENCES public.cars(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

\unrestrict YSzwCmEe8WnJJSEfwuGUVd3ByFwfTlNw1DJWwKhy1hedm3fkxUQ6j0ou9vBHydJ

