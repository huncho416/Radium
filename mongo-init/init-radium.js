// MongoDB initialization script for Radium
// This script creates the radium database and sets up initial collections

// Switch to the radium database
db = db.getSiblingDB('radium');

// Create collections with initial indexes
db.createCollection('profiles');
db.createCollection('ranks');
db.createCollection('punishments');

// Create indexes for better performance
db.profiles.createIndex({ "uuid": 1 }, { unique: true });
db.profiles.createIndex({ "username": 1 });
db.profiles.createIndex({ "lastSeen": 1 });

db.ranks.createIndex({ "name": 1 }, { unique: true });
db.ranks.createIndex({ "priority": 1 });

db.punishments.createIndex({ "playerId": 1 });
db.punishments.createIndex({ "type": 1 });
db.punishments.createIndex({ "issuedAt": 1 });
db.punishments.createIndex({ "expiresAt": 1 });
db.punishments.createIndex({ "active": 1 });
db.punishments.createIndex({ "ip": 1 });

// Insert default rank
db.ranks.insertOne({
    "name": "default",
    "priority": 0,
    "prefix": "&7",
    "suffix": "",
    "color": "&7",
    "permissions": [],
    "inheritance": [],
    "metadata": {},
    "isDefault": true
});

print("Radium database initialized successfully!");
print("Collections created: profiles, ranks, punishments");
print("Indexes created for optimal performance");
print("Default rank inserted");
