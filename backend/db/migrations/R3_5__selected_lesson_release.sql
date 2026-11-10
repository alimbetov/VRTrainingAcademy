-- R3 stored learner selection. Run AFTER R3_4.
-- Release ID is a trusted result from Content Domain, not client-supplied.
ALTER TABLE education.learning_attempt ADD COLUMN selected_release_id varchar(128);
CREATE INDEX idx_attempt_selected_release ON education.learning_attempt(selected_release_id)
    WHERE selected_release_id IS NOT NULL;
-- Non-null validation for new attempts belongs to the dedicated selected
-- attempt code path. Older attempts remain backwards-compatible.
