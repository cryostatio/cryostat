#!/usr/bin/env bash

psql -d template1 -c "CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public CASCADE;"
