INSERT INTO version (version, singleton_value)
VALUES (?, 0)
ON CONFLICT (singleton_value) DO UPDATE SET version = excluded.version;
