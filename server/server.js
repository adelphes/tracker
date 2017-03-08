const http = require('http'),
    fs = require('fs'),
    path = require('path'),
    MongoClient = require('mongodb').MongoClient,
    dburl = 'mongodb://localhost:27018/tracker',
    IP='0.0.0.0', PORT = 8060,
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

function saveLocationUpdates(id, locations) {
    return new Promise((res,rej) => {
        MongoClient.connect(dburl, function (err, db) {
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
        MongoClient.connect(dburl, function (err, db) {
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
