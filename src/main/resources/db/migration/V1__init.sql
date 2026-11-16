-- V1 初始结构：只增不删（NFR-001、FR-030）。已提交的迁移文件永不修改。
CREATE TABLE app_user (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  login_name    VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(100) NULL,
  display_name  VARCHAR(100) NOT NULL,
  role          VARCHAR(16)  NOT NULL CHECK (role IN ('EMPLOYEE','IT_STAFF','ADMIN')),
  created_at    TIMESTAMPTZ  NOT NULL,
  created_by    BIGINT NULL REFERENCES app_user(id),
  CONSTRAINT ck_app_user_password CHECK (password_hash IS NOT NULL OR role = 'ADMIN')
);

CREATE TABLE category (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(50) NOT NULL,
  active      BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL,
  removed_at  TIMESTAMPTZ NULL
);
CREATE UNIQUE INDEX ux_category_active_name ON category(name) WHERE active;

CREATE TABLE ticket (
  id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title                       VARCHAR(200) NOT NULL,
  description                 TEXT         NOT NULL,
  category_id                 BIGINT       NOT NULL REFERENCES category(id),
  urgency                     VARCHAR(8)   NOT NULL CHECK (urgency IN ('LOW','MEDIUM','HIGH')),
  submitter_id                BIGINT       NOT NULL REFERENCES app_user(id),
  submitted_at                TIMESTAMPTZ  NOT NULL,
  status                      VARCHAR(16)  NOT NULL CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED')),
  assignee_id                 BIGINT       NULL REFERENCES app_user(id),
  first_resolved_at           TIMESTAMPTZ  NULL,
  first_resolved_assignee_id  BIGINT       NULL REFERENCES app_user(id),
  updated_at                  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_ticket_submitter ON ticket(submitter_id, submitted_at DESC);
CREATE INDEX ix_ticket_submitted ON ticket(submitted_at DESC);
CREATE INDEX ix_ticket_status    ON ticket(status);
CREATE INDEX ix_ticket_category  ON ticket(category_id);
CREATE INDEX ix_ticket_assignee  ON ticket(assignee_id);
CREATE INDEX ix_ticket_first_resolved ON ticket(first_resolved_at);

CREATE TABLE ticket_comment (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ticket_id   BIGINT      NOT NULL REFERENCES ticket(id),
  author_id   BIGINT      NOT NULL REFERENCES app_user(id),
  author_role VARCHAR(16) NOT NULL CHECK (author_role IN ('EMPLOYEE','IT_STAFF')),
  body        TEXT        NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_comment_ticket ON ticket_comment(ticket_id, created_at, id);

CREATE TABLE ticket_event (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ticket_id         BIGINT      NOT NULL REFERENCES ticket(id),
  actor_id          BIGINT      NOT NULL REFERENCES app_user(id),
  event_type        VARCHAR(16) NOT NULL CHECK (event_type IN ('CLAIM','ASSIGN','STATUS')),
  from_assignee_id  BIGINT NULL REFERENCES app_user(id),
  to_assignee_id    BIGINT NULL REFERENCES app_user(id),
  from_status       VARCHAR(16) NULL,
  to_status         VARCHAR(16) NULL,
  created_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_event_ticket ON ticket_event(ticket_id, created_at, id);
