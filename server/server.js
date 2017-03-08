'use strict'

const http = require('http'),
    fs = require('fs'),
    path = require('path'),
    MongoClient = require('mongodb').MongoClient,
    IP='0.0.0.0', PORT = 80, // PORT = 8060,
    track_html_fpn = path.join(__dirname, 'track.html');

const mimetypes = {
	html:'text/html',
	css:'text/css',
	txt:'text/plain',
	java:'text/plain',
	png:'image/png',
	gif:'image/gif',
	svg:'image/svg+xml',
	json:'application/json',
	js:'application/javascript',
	woff:'application/font-woff'
};

var track_html = fs.readFileSync(track_html_fpn, 'utf8'),
    track_html_stat_mtime = fs.statSync(track_html_fpn).mtime.getTime();

// setup the database stuff - this is only done once during startup, so it's all synchronous
const mongodb_folder = path.join(__dirname, 'data'),
    mongodb_port = 27018,
    mongodb_url = `mongodb://localhost:${mongodb_port}/tracker`;
try { fs.mkdirSync(mongodb_folder); }
catch(ex) {}
// start the mongodb server
require('child_process').spawn('/usr/bin/mongod', [`--dbpath=${mongodb_folder}`,`--port=${mongodb_port}`]);

(function wipeAtMidnight() {
    var millis_per_day = (24 * 60 * 60 * 1000);
    var millis_until_midnight = millis_per_day - (new Date().getTime() % millis_per_day);
    setTimeout(() => {
        // wipe the db
        clearLocationUpdates();
        // and do it again tomorrow...
        wipeAtMidnight();
    }, millis_until_midnight);
})();

function clearLocationUpdates() {
    return new Promise((res, rej) => {
        MongoClient.connect(mongodb_url, function (err, db) {
            if (err) return rej(err);
            // get a list of all the collections
            db.listCollections().toArray(function (err, collections) {
                if (err) {
                    db.close();
                    return rej(err);
                }
                console.log(`Dropping ${collections.length} collections`);
                // drop them one at a time
                const dropNext = coll => {
                    if (!coll.length) {
                        db.close();
                        return res();   // finished
                    }
                    db.collection(coll.shift().name).drop(err => {
                        dropNext(coll);
                    });
                }
                dropNext(collections);
            });
        });
    })
}

function saveLocationUpdates(id, locations) {
    return new Promise((res,rej) => {
        MongoClient.connect(mongodb_url, function (err, db) {
            if (err) return rej(err);
            // use one collection per tracker id
            var locs = db.collection('locations-'+id);
            locs.insertMany(locations, (err, result) => {
                db.close();
                if (err) return rej(err);
                res();
            });
        });
    })
}

function completeRequest(res, code, info) {
    res.writeHead(code, { 'content-type': mimetypes.json });
    res.end(JSON.stringify(info||{}));
}

function check_update(locations) {
    if (!Array.isArray(locations)) throw new Error('Update is not an array');
    var badentry = locations.find(loc => {
        if (!loc || typeof loc !== 'object')
            return true;
        // check the object keya are what we expect
        if (Object.keys(loc).sort().join(',') !== 'lat,long,time')
            return true;
    });
    if (badentry) throw new Error('Update contains invalid data');
}

function getLocations(id) {
    return new Promise((res, rej) => {
        MongoClient.connect(mongodb_url, function (err, db) {
            if (err) return rej(err);
            var locs = db.collection('locations-'+id);
            locs.find({ }).toArray(function (err, locations) {
                db.close();
                if (err) return rej(err);
                res(locations);
            });
        });
    })
}

function onTrackClientHTML(req, res) {
    if (!/^get$/i.test(req.method)) {
        completeRequest(res, 405); // method not allowed
        return;
    }
    // just return the (self-contained) html page
    if (!track_html) {
        completeRequest(res, 500);
        return;
    }
    if (track_html_stat_mtime) {
        // dev use only - see if the html has changed
        const mtime = fs.statSync(track_html_fpn).mtime.getTime();
        if (mtime > track_html_stat_mtime) {
            // file has a later timestamp - reload it
            track_html = fs.readFileSync(track_html_fpn, 'utf8');
            track_html_stat_mtime = mtime;
        }
    }
    res.writeHead(200, { 
        'content-type': mimetypes.html,
        'content-length': ''+track_html.length,
        'cache-control': 'no-cache',
    });
    res.end(track_html);
}

const requestHandler = {
    v1: {
        get(req, res, trackerid) {
            if (!/^get$/i.test(req.method)) {
                completeRequest(res, 405); // method not allowed
                return;
            }
            getLocations(trackerid)
                .then(locations => completeRequest(res, 200, { data: locations }))
                .catch(err => completeRequest(res, 500, { ex: err }));
        },
        update(req, res, trackerid) {
            if (!/^post$/i.test(req.method)) {
                completeRequest(res, 405); // method not allowed
                return;
            }
            var chunks = [], total_length = 0;
            req.on('data', chunk => {
                chunks.push(chunk);
                total_length += chunk.length;
                if (total_length > 1e6) {
                    // kill any connections trying to upload > 1 MB
                    req.destroy();
                }
            })
            .on('end', () => {
                try {
                    var locations = JSON.parse(Buffer.concat(chunks).toString('utf8'));
                    // make sure the location data is what we expect
                    check_update(locations);
                    // filter out any locations from the client that are not from today
                    var today = new Date().toISOString().slice(0, 'YYYY-MM-DD'.length);
                    locations = locations.filter(loc => loc.time.slice(0,today.length) === today);

                } catch (e) {
                    completeRequest(res, 400, { error: "Data validation failed", ex: e });
                    return;
                }
                // save the info to the mongo DB
                saveLocationUpdates(trackerid, locations)
                    .then(() => completeRequest(res, 200))
                    .catch(err => completeRequest(res, 500, { error: "Database update failed", ex: err }));
            });
        }
    }
}

http.createServer(function (req, res) {

    if (req.url === '/')
        return onTrackClientHTML(req, res);
    
    // we could use express for this, but it's such a simple API:
    // /v1/locations/<tracker id>/get
    // /v1/locations/<tracker id>/update
    var m = req.url.match(/^\/(v\d+)\/locations\/(\d+)\/(.+)/);
    var api_handler = m && requestHandler[m[1]] && requestHandler[m[1]][m[3]];
    if (api_handler) {
        return api_handler(req, res, parseInt(m[2]));
    }

    res.writeHead(404); // Not found
    res.end();

}).listen(PORT, IP);

console.info('Tracker server running: http://'+IP+':'+PORT+'/');
