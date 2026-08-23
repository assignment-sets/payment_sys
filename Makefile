-include .env
export

.PHONY: dev reconcile reconcile-freeze
dev:
	mvn spring-boot:run

reconcile:
	bash scripts/reconciliation/run.sh

reconcile-freeze:
	bash scripts/reconciliation/run.sh --freeze