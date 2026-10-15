"""Small VT screen model for checking the actual PTY frame, rather than raw output alone."""
import codecs
import unicodedata


class Screen:
    def __init__(self, rows, columns):
        self.rows, self.columns = rows, columns
        self.decoder = codecs.getincrementaldecoder("utf-8")("replace")
        self.mode = "text"
        self.sequence = ""
        self.primary = None
        self.reset()

    def reset(self):
        self.lines = [[" "] * self.columns for _ in range(self.rows)]
        self.row = self.column = 0
        self.top, self.bottom = 0, self.rows - 1
        self.saved = (0, 0)
        self.last = " "

    def resize(self, rows, columns):
        old = self.lines
        self.rows, self.columns = rows, columns
        self.lines = [(line[:columns] + [" "] * columns)[:columns] for line in old[:rows]]
        self.lines += [[" "] * columns for _ in range(rows - len(self.lines))]
        self.row, self.column = min(self.row, rows - 1), min(self.column, columns - 1)
        self.top, self.bottom = 0, rows - 1

    def index(self):
        if self.row == self.bottom:
            del self.lines[self.top]
            self.lines.insert(self.bottom, [" "] * self.columns)
        else:
            self.row = min(self.rows - 1, self.row + 1)

    def put(self, char):
        if unicodedata.combining(char):
            return
        width = 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1
        if self.column + width > self.columns:
            self.column = 0
            self.index()
        self.lines[self.row][self.column] = char
        if width == 2:
            self.lines[self.row][self.column + 1] = ""
        self.column += width
        self.last = char

    def csi(self, sequence):
        command, params = sequence[-1], sequence[:-1]
        private = params.startswith("?")
        try:
            values = [int(x) if x else 0 for x in params.lstrip("?=>").split(";")]
        except ValueError:
            return
        n = values[0] or 1
        if private:
            if 1049 in values:
                if command == "h":
                    self.primary = (self.lines, self.row, self.column)
                    self.reset()
                elif command == "l" and self.primary:
                    self.lines, self.row, self.column = self.primary
                    self.primary = None
            return
        if command in ("H", "f"):
            self.row = min(self.rows - 1, n - 1)
            self.column = min(self.columns - 1, (values[1] or 1) - 1 if len(values) > 1 else 0)
        elif command in ("G", "`"):
            self.column = min(self.columns - 1, n - 1)
        elif command == "d":
            self.row = min(self.rows - 1, n - 1)
        elif command in ("A", "F"):
            self.row = max(0, self.row - n)
            if command == "F": self.column = 0
        elif command in ("B", "E", "e"):
            self.row = min(self.rows - 1, self.row + n)
            if command == "E": self.column = 0
        elif command in ("C", "a"):
            self.column = min(self.columns - 1, self.column + n)
        elif command == "D":
            self.column = max(0, self.column - n)
        elif command == "r":
            self.top = min(self.rows - 1, n - 1)
            self.bottom = min(self.rows - 1, (values[1] or self.rows) - 1) if len(values) > 1 else self.rows - 1
            self.row = self.column = 0
        elif command == "K":
            start = 0 if values[0] in (1, 2) else min(self.column, self.columns - 1)
            end = self.columns if values[0] in (0, 2) else min(self.column + 1, self.columns)
            self.lines[self.row][start:end] = [" "] * (end - start)
        elif command == "J":
            if values[0] in (2, 3):
                self.lines = [[" "] * self.columns for _ in range(self.rows)]
            elif values[0] == 0:
                self.lines[self.row][min(self.column, self.columns):] = [" "] * max(0, self.columns - self.column)
                for row in range(self.row + 1, self.rows): self.lines[row] = [" "] * self.columns
        elif command in ("L", "M"):
            for _ in range(min(n, self.rows)):
                if command == "L":
                    self.lines.insert(self.row, [" "] * self.columns)
                    del self.lines[self.bottom + 1]
                else:
                    del self.lines[self.row]
                    self.lines.insert(self.bottom, [" "] * self.columns)
        elif command in ("P", "@", "X"):
            x = min(self.column, self.columns - 1)
            line = self.lines[self.row]
            if command == "P": line[x:] = (line[x + n:] + [" "] * n)[:self.columns - x]
            elif command == "@": line[x:] = ([" "] * n + line[x:])[:self.columns - x]
            else: line[x:min(x + n, self.columns)] = [" "] * min(n, self.columns - x)
        elif command == "b":
            for _ in range(min(n, self.columns)): self.put(self.last)
        elif command == "s": self.saved = (self.row, self.column)
        elif command == "u": self.row, self.column = self.saved

    def feed(self, data):
        for char in self.decoder.decode(data):
            if self.mode == "csi":
                self.sequence += char
                if "@" <= char <= "~":
                    self.csi(self.sequence)
                    self.mode = "text"
            elif self.mode == "escape":
                if char == "[": self.mode, self.sequence = "csi", ""
                elif char == "]": self.mode = "osc"
                elif char in "()": self.mode = "charset"
                else:
                    if char == "7": self.saved = (self.row, self.column)
                    elif char == "8": self.row, self.column = self.saved
                    elif char == "D": self.index()
                    elif char == "M": self.row = max(0, self.row - 1)
                    self.mode = "text"
            elif self.mode == "osc":
                if char == "\a": self.mode = "text"
                elif char == "\x1b": self.mode = "osc-end"
            elif self.mode == "osc-end": self.mode = "text" if char == "\\" else "osc"
            elif self.mode == "charset": self.mode = "text"
            elif char == "\x1b": self.mode = "escape"
            elif char == "\r": self.column = 0
            elif char == "\n": self.index()
            elif char == "\b": self.column = max(0, self.column - 1)
            elif char == "\t": self.column = min(self.columns - 1, (self.column // 8 + 1) * 8)
            elif char >= " " and char != "\x7f": self.put(char)

    def text(self):
        return ["".join(line).rstrip() for line in self.lines]
