-include .env
export

.PHONY: dev
dev:
	mvn spring-boot:run