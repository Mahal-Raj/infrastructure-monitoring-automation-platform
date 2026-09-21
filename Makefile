.PHONY: build test demo compose-up compose-down

build:
	bash scripts/build.sh

test:
	bash scripts/test.sh

demo:
	bash scripts/run-demo.sh

compose-up:
	docker compose up --build -d

compose-down:
	docker compose down
