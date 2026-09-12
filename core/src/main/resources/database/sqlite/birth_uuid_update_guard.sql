CREATE TRIGGER cauldrons_birth_uuid_update BEFORE UPDATE ON cauldrons
WHEN typeof(NEW.birth_uuid) <> 'blob' OR length(NEW.birth_uuid) <> 16
BEGIN
    SELECT RAISE(ABORT, 'Invalid cauldron birth UUID');
END;
