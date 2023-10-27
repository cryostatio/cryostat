#!/usr/bin/env bash

psql <<< "
create schema extensions;

grant usage on schema extensions to public;
grant execute on all functions in schema extensions to public;

alter default privileges in schema extensions
   grant execute on functions to public;

alter default privileges in schema extensions
   grant usage on types to public;
"

for extension in pgcrypto ; do
    psql -d template1 -c "CREATE EXTENSION ${extension};"
done
