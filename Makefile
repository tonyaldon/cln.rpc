.PHONY: pytest cljtest test

cljtest:
	clojure -X:test

pytest:
	pytest pytest

test: cljtest pytest
