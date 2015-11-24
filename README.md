# typefest

A simple game created to learn typing. This can be played over a network. It uses zmq for messaging both in single-user and distributed mode.
It uses lanterna to build a simple terminal based ui. There are falling words which one needs to type to get points.

-- to play in single user mode
lein run

-- to play in distributed 

lein run server

-- and every client will run

lein run client