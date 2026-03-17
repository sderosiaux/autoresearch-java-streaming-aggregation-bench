CC = gcc
CFLAGS = -O3 -march=native -pthread
TARGET = streaming_aggregator

$(TARGET): src/streaming_aggregator.c
	$(CC) $(CFLAGS) -o $@ $<

clean:
	rm -f $(TARGET)

.PHONY: clean
