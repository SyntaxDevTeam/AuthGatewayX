# Database, backups and updates

**Language / Język:** English · [Polski](../Baza-danych.md)

[Home](Home.md) · [Configuration](Configuration.md) · [Account identity verification](Premium.md)

## Start with SQLite

Accounts are stored in `plugins/LoginCoreX/logincorex.db` by default. Keep the database across restarts and upgrades. Do not open the same SQLite file concurrently from independent servers.

## MySQL, MariaDB and PostgreSQL

Create a database and a dedicated user, then enter your hosting provider's connection details. Check database connectivity and player login on a server copy before switching your live server.

```yaml
storage:
  type: MYSQL
  sqlite-file: logincorex.db
  pool-size: 2
  remote:
    host: "127.0.0.1"
    port: 3306
    database: "logincorex"
    username: "logincorex"
    password: "REPLACE_WITH_YOUR_PASSWORD"
    parameters: ""
```

Use your actual values. Select `MARIADB` for MariaDB or `POSTGRESQL` for PostgreSQL, normally on port `5432`. Agree TLS requirements and connection parameters with your host. `LOGINCOREX_DB_PASSWORD` overrides the YAML password. Never publish credentials or an unredacted configuration.

## Back up and restore

1. Stop the server so data cannot change while copying.
2. With SQLite, copy the whole plugin folder, including any companion SQLite files.
3. With a remote database, take a full database backup using your host's tooling and copy the plugin folder too.
4. Record the plugin and server versions.
5. Keep the backup outside the running server's directory with restricted access.
6. Test restoration on a private environment. The existence of a backup file alone does not prove it is usable.

## Update safely

Back up, stop the server and replace the JAR. Test the update on a copy first. Compare configuration changes and check existing login, registration and restart behavior. Do not delete the database to refresh an installation.

If the data format changed, reverting only the JAR may be insufficient. Restore matching plugin, configuration and database backups while the server is stopped.

## Move accounts

Changing `storage.type` does not migrate accounts. A newly connected empty database looks like a fresh installation; do not expose it before moving and verifying existing records.

There is no ready-made account importer or migration command for other authentication plugins. Do not copy their tables blindly. Account UUID migration following verified Minecraft identity linking is a separate operation described in [Account identity verification](Premium.md).
