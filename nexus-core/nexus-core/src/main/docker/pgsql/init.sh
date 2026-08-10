#!/usr/bin/env bash
if [ "$NEX_POSTGRES_USER" = "" ]; then
	export NEX_POSTGRES_USER="replica"
fi
if [ "$NEX_POSTGRES_PASSWORD" = "" ]; then
	export NEX_POSTGRES_PASSWORD="replica"
fi
psql -U $POSTGRES_USER --no-password -c "CREATE USER $NEX_POSTGRES_USER WITH PASSWORD '$NEX_POSTGRES_PASSWORD'"
psql -U $NEX_POSTGRES_USER --no-password -d postgres -f /tmp/ddl.sql 
