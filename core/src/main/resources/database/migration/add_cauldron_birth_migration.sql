ALTER TABLE cauldrons ADD birth_uuid BLOB NOT NULL DEFAULT X'';
UPDATE cauldrons SET birth_uuid = randomblob(16);
