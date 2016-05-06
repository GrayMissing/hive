CREATE TABLE "default"."AUDIT_LOGS"
 ( "RUN_ID" VARCHAR2(36) NOT NULL ENABLE,
   "FILE_NAME" VARCHAR2(255) NOT NULL ENABLE,
   "RUN_DATE" DATE NOT NULL ENABLE,
   CONSTRAINT "AUDIT_LOGS_PK" PRIMARY KEY ("RUN_ID") ENABLE
 ); 