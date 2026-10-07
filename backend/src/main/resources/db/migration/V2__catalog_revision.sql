CREATE TABLE tf_catalog_revision (
  id TINYINT NOT NULL PRIMARY KEY,
  revision BIGINT NOT NULL,
  CHECK (id = 1 AND revision >= 0)
) ENGINE=InnoDB;
INSERT INTO tf_catalog_revision(id, revision) VALUES (1, 0);
