-- Entitlements: comma-separated access groups; empty = every caller may retrieve the document.
ALTER TABLE documents ADD COLUMN access_groups VARCHAR(500) DEFAULT '' NOT NULL;
